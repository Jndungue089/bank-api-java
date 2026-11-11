package com.sb24.api.service;

import com.sb24.api.access.Perm;
import com.sb24.api.access.PolicyService;
import com.sb24.api.access.Resource;
import com.sb24.api.access.Subject;
import com.sb24.api.domain.*;
import com.sb24.api.domain.LedgerEntry.Direction;
import com.sb24.api.domain.LedgerEntry.Kind;
import com.sb24.api.queue.NotificationService;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.SavingsPlanRepository;
import com.sb24.api.repo.SavingsProductRepository;
import com.sb24.api.util.Hashing;
import com.sb24.api.web.Dtos.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Savings: flexible accounts and term deposits. A savings account is a real ledger account (type SAVINGS) owned by the
 * customer; money moves to and from the customer's current account with double-entry postings. Term deposits are
 * locked until maturity by the SavingsLockPolicy (ABAC). Interest is credited daily by InterestService.
 */
@Service
public class SavingsService {
    private final AccountRepository accounts;
    private final SavingsPlanRepository plans;
    private final SavingsProductRepository products;
    private final AccountService accountService;
    private final LedgerService ledgerService;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final PolicyService policy;
    private final NotificationService notifications;
    private final TransactionTemplate tx;

    public SavingsService(AccountRepository accounts, SavingsPlanRepository plans, SavingsProductRepository products, AccountService accountService,
                          LedgerService ledgerService, IdempotencyService idempotency, AuditService audit, PolicyService policy,
                          NotificationService notifications, PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.plans = plans;
        this.products = products;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
        this.idempotency = idempotency;
        this.audit = audit;
        this.policy = policy;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(txManager);
    }

    @Transactional(readOnly = true)
    public List<SavingsProductView> products(UUID userId) {
        policy.requirePermission(Subject.customer(userId), Perm.SAVINGS_READ);
        return products.findByEnabledTrueOrderByCodeAsc().stream()
                .map(p -> new SavingsProductView(p.getCode(), p.getName(), p.getAnnualRate(), p.getMinAmount(), p.getTermDays())).toList();
    }

    @Transactional(readOnly = true)
    public List<SavingsView> list(UUID userId) {
        policy.requirePermission(Subject.customer(userId), Perm.SAVINGS_READ);
        return plans.findByUserIdOrderByOpenedAtAsc(userId).stream().map(this::view).toList();
    }

    public IdempotencyService.Result<SavingsReceipt> subscribe(UUID userId, SubscribeSavingsRequest r, String key) {
        String hash = Hashing.sha256Hex(String.join("|", "SUB", r.fromAccountId().toString(), r.productCode().toUpperCase(), r.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), String.valueOf(r.goalName())));
        return run(userId, key, hash, "SAVINGS_SUBSCRIBE", r.fromAccountId().toString(), () -> doSubscribe(userId, r));
    }

    public IdempotencyService.Result<SavingsReceipt> deposit(UUID userId, UUID savingsAccountId, SavingsMoveRequest r, String key) {
        String hash = Hashing.sha256Hex(String.join("|", "DEP", savingsAccountId.toString(), r.currentAccountId().toString(), r.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        return run(userId, key, hash, "SAVINGS_DEPOSIT", savingsAccountId.toString(), () -> doMove(userId, savingsAccountId, r, true));
    }

    public IdempotencyService.Result<SavingsReceipt> withdraw(UUID userId, UUID savingsAccountId, SavingsMoveRequest r, String key) {
        String hash = Hashing.sha256Hex(String.join("|", "WDR", savingsAccountId.toString(), r.currentAccountId().toString(), r.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        return run(userId, key, hash, "SAVINGS_WITHDRAW", savingsAccountId.toString(), () -> doMove(userId, savingsAccountId, r, false));
    }

    private IdempotencyService.Result<SavingsReceipt> run(UUID userId, String key, String hash, String action, String resourceId, Supplier<SavingsReceipt> op) {
        try {
            IdempotencyService.Result<SavingsReceipt> result = tx.execute(s -> idempotency.execute(userId, key, hash, SavingsReceipt.class, op));
            if (!result.replayed()) audit.customer(userId, action, "SUCCESS", "SAVINGS", result.body().savingsAccountId().toString(), "amount=" + result.body().amount());
            return result;
        } catch (ApiException e) {
            audit.customer(userId, action, "DENIED", "ACCOUNT", resourceId, e.code());
            throw e;
        }
    }

    private SavingsReceipt doSubscribe(UUID userId, SubscribeSavingsRequest r) {
        SavingsProduct product = products.findById(r.productCode().toUpperCase()).filter(SavingsProduct::isEnabled)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_PRODUCT", "Produto de poupança desconhecido."));
        BigDecimal amount = r.amount().setScale(2, RoundingMode.UNNECESSARY);
        if (amount.compareTo(product.getMinAmount()) < 0) {
            throw ApiException.unprocessable("BELOW_MINIMUM", "Montante mínimo para " + product.getName() + ": " + product.getMinAmount() + " AOA.");
        }
        Account savings = accountService.create(userId, AccountType.SAVINGS, null);
        List<Account> locked = accounts.lockAllById(Stream.of(r.fromAccountId(), savings.getId()).sorted().toList());
        Account src = find(locked, r.fromAccountId());
        if (src == null) throw ApiException.notFound("Conta não encontrada.");
        policy.require(Subject.customer(userId), Perm.SAVINGS_MANAGE, Resource.account(src, Map.of("moneyOut", true, "requiredType", "CURRENT", "amount", amount, "ownAccounts", true)));
        if (src.available().compareTo(amount) < 0) throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Saldo disponível insuficiente.");

        SavingsPlan plan = new SavingsPlan();
        plan.setAccountId(savings.getId());
        plan.setUserId(userId);
        plan.setProductCode(product.getCode());
        plan.setGoalName(r.goalName() == null ? null : r.goalName().trim());
        plan.setAnnualRate(product.getAnnualRate());
        if (product.getTermDays() > 0) plan.setMaturityDate(LocalDate.now(TransferService.LUANDA).plusDays(product.getTermDays()));
        plans.save(plan);
        UUID ref = post(src, find(locked, savings.getId()), amount, "Subscrição " + product.getName());
        notifications.notify(userId, product.getName() + " subscrita com " + amount + " AOA.");
        return receipt(ref, "SAVINGS_SUBSCRIBE", amount, find(locked, savings.getId()), src);
    }

    private SavingsReceipt doMove(UUID userId, UUID savingsId, SavingsMoveRequest r, boolean deposit) {
        BigDecimal amount = r.amount().setScale(2, RoundingMode.UNNECESSARY);
        SavingsPlan plan = plans.findById(savingsId).orElseThrow(() -> ApiException.notFound("Poupança não encontrada."));
        List<Account> locked = accounts.lockAllById(Stream.of(savingsId, r.currentAccountId()).distinct().sorted().toList());
        Account savings = find(locked, savingsId), current = find(locked, r.currentAccountId());
        if (savings == null || current == null) throw ApiException.notFound("Conta não encontrada.");
        Subject me = Subject.customer(userId);
        if (deposit) {
            policy.require(me, Perm.SAVINGS_MANAGE, Resource.account(savings, Map.of("operation", "DEPOSIT")));
            policy.require(me, Perm.SAVINGS_MANAGE, Resource.account(current, Map.of("moneyOut", true, "requiredType", "CURRENT", "amount", amount, "ownAccounts", true)));
            if (!"ACTIVE".equals(plan.getStatus())) throw ApiException.unprocessable("PLAN_CLOSED", "Poupança encerrada.");
            if (current.available().compareTo(amount) < 0) throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Saldo disponível insuficiente.");
            UUID ref = post(current, savings, amount, "Depósito em poupança");
            return receipt(ref, "SAVINGS_DEPOSIT", amount, savings, current);
        }
        Map<String, Object> attrs = new HashMap<>(Map.of("moneyOut", true, "requiredType", "SAVINGS", "operation", "WITHDRAW", "amount", amount, "ownAccounts", true));
        if (plan.getMaturityDate() != null) attrs.put("maturityDate", plan.getMaturityDate());
        policy.require(me, Perm.SAVINGS_MANAGE, Resource.account(savings, attrs));                    // ownership + lock until maturity
        policy.require(me, Perm.SAVINGS_MANAGE, Resource.account(current, Map.of("operation", "RECEIVE"))); // destination must be mine too
        if (current.getType() != AccountType.CURRENT) throw ApiException.unprocessable("WRONG_ACCOUNT_TYPE", "O destino tem de ser uma conta à ordem.");
        if (savings.available().compareTo(amount) < 0) throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Saldo da poupança insuficiente.");
        UUID ref = post(savings, current, amount, "Levantamento de poupança");
        return receipt(ref, "SAVINGS_WITHDRAW", amount, savings, current);
    }

    /** Debit `from`, credit `to`, with matching ledger lines. Both accounts must be locked by the caller. */
    private UUID post(Account from, Account to, BigDecimal amount, String description) {
        UUID ref = UUID.randomUUID();
        from.setBalance(from.getBalance().subtract(amount));
        to.setBalance(to.getBalance().add(amount));
        ledgerService.append(from, ref, Kind.SAVINGS, Direction.DEBIT, amount, to.getIban(), description);
        ledgerService.append(to, ref, Kind.SAVINGS, Direction.CREDIT, amount, from.getIban(), description);
        return ref;
    }

    private SavingsReceipt receipt(UUID ref, String type, BigDecimal amount, Account savings, Account current) {
        return new SavingsReceipt(ref, type, savings.getId(), savings.getIban(), amount, savings.getBalance(), current.available(), Instant.now());
    }

    private SavingsView view(SavingsPlan p) {
        Account a = accounts.findById(p.getAccountId()).orElseThrow();
        String name = products.findById(p.getProductCode()).map(SavingsProduct::getName).orElse(p.getProductCode());
        return new SavingsView(a.getId(), a.getIban(), p.getProductCode(), name, p.getGoalName(), p.getAnnualRate(), p.getMaturityDate(), a.getBalance(), p.getStatus(), p.getOpenedAt());
    }

    private static Account find(List<Account> list, UUID id) { return list.stream().filter(a -> a.getId().equals(id)).findFirst().orElse(null); }
}
