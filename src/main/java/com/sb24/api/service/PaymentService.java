package com.sb24.api.service;

import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.Account;
import com.sb24.api.domain.LedgerEntry.Direction;
import com.sb24.api.domain.LedgerEntry.Kind;
import com.sb24.api.domain.PaymentProvider;
import com.sb24.api.queue.NotificationService;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.LedgerRepository;
import com.sb24.api.repo.PaymentProviderRepository;
import com.sb24.api.util.Hashing;
import com.sb24.api.web.Dtos.PaymentRequest;
import com.sb24.api.web.Dtos.ProviderView;
import com.sb24.api.web.Dtos.Receipt;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bill payments (telecom / utilities). Providers are reference data in the DB (seeded at start-up). */
@Service
public class PaymentService {
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final PaymentProviderRepository providers;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final LedgerService ledgerService;
    private final NotificationService notifications;
    private final AppProperties props;
    private final com.sb24.api.access.PolicyService policy;
    private final TransactionTemplate tx;

    public PaymentService(AccountRepository accounts, LedgerRepository ledger, PaymentProviderRepository providers,
                          IdempotencyService idempotency, AuditService audit, LedgerService ledgerService,
                          NotificationService notifications, AppProperties props, com.sb24.api.access.PolicyService policy,
                          PlatformTransactionManager txManager) {
        this.policy = policy;
        this.accounts = accounts;
        this.ledger = ledger;
        this.providers = providers;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ledgerService = ledgerService;
        this.notifications = notifications;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public List<ProviderView> providers() {
        return providers.findByEnabledTrueOrderByCodeAsc().stream().map(p -> new ProviderView(p.getCode(), p.getName(), p.getReferenceHint())).toList();
    }

    public IdempotencyService.Result<Receipt> pay(UUID userId, com.sb24.api.web.Dtos.PaymentRequest r, String idemKey, String ip) {
        String hash = Hashing.sha256Hex(String.join("|", "P", r.fromAccountId().toString(), r.providerCode(), r.reference(),
                r.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        try {
            IdempotencyService.Result<Receipt> result = tx.execute(status ->
                    idempotency.execute(userId, idemKey, hash, Receipt.class, () -> doPay(userId, r)));
            if (!result.replayed()) {
                audit.customer(userId, "PAYMENT", "SUCCESS", "PAYMENT", result.body().reference().toString(),
                        "provider=" + r.providerCode().toUpperCase() + " amount=" + result.body().amount());
            }
            return result;
        } catch (ApiException e) {
            audit.customer(userId, "PAYMENT", "DENIED", "ACCOUNT", r.fromAccountId().toString(), e.code());
            throw e;
        }
    }

    private Receipt doPay(UUID userId, PaymentRequest r) {
        PaymentProvider provider = providers.findById(r.providerCode().toUpperCase()).filter(PaymentProvider::isEnabled)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_PROVIDER", "Entidade desconhecida."));
        if (!r.reference().matches(provider.getReferencePattern())) {
            throw ApiException.unprocessable("INVALID_REFERENCE", "Referência inválida para " + provider.getName() + ".");
        }
        BigDecimal amount = r.amount().setScale(2, RoundingMode.UNNECESSARY);
        if (amount.compareTo(props.banking().perTransactionLimit()) > 0) {
            throw ApiException.unprocessable("LIMIT_EXCEEDED", "Excede o limite por operação.");
        }
        UUID settlementId = accounts.findIdByPurpose("PAYMENTS").orElseThrow();
        List<Account> locked = accounts.lockAllById(Stream.of(r.fromAccountId(), settlementId).sorted().toList());
        Account src = locked.stream().filter(a -> a.getId().equals(r.fromAccountId())).findFirst().orElse(null);
        if (src == null) throw ApiException.notFound("Conta não encontrada.");
        Account settlement = locked.stream().filter(a -> a.getId().equals(settlementId)).findFirst().orElseThrow();
        policy.require(com.sb24.api.access.Subject.customer(userId), com.sb24.api.access.Perm.PAYMENT_CREATE, com.sb24.api.access.Resource.account(src,
                java.util.Map.of("moneyOut", true, "requiredType", "CURRENT", "amount", amount)));

        Instant startOfDay = LocalDate.now(TransferService.LUANDA).atStartOfDay(TransferService.LUANDA).toInstant();
        if (ledger.debitedSince(src.getId(), startOfDay).add(src.getHeld()).add(amount).compareTo(props.banking().dailyLimit()) > 0) {
            throw ApiException.unprocessable("DAILY_LIMIT_EXCEEDED", "Excede o limite diário.");
        }
        if (src.available().compareTo(amount) < 0) throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Saldo disponível insuficiente.");

        UUID ref = UUID.randomUUID();
        src.setBalance(src.getBalance().subtract(amount));
        settlement.setBalance(settlement.getBalance().add(amount));
        String description = provider.getName() + " ref " + r.reference();
        ledgerService.append(src, ref, Kind.PAYMENT, Direction.DEBIT, amount, settlement.getIban(), description);
        ledgerService.append(settlement, ref, Kind.PAYMENT, Direction.CREDIT, amount, src.getIban(), description);
        notifications.notify(userId, "Pagamento de " + amount + " AOA a " + provider.getName() + " efectuado.");
        return new Receipt(ref, "COMPLETED", "PAYMENT", amount, BigDecimal.ZERO.setScale(2), src.getIban(), settlement.getIban(),
                src.getBalance(), src.available(), Instant.now());
    }
}
