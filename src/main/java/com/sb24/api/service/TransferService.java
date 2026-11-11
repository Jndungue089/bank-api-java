package com.sb24.api.service;

import com.sb24.api.access.Perm;
import com.sb24.api.access.PolicyService;
import com.sb24.api.access.Resource;
import com.sb24.api.access.Subject;
import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.*;
import com.sb24.api.domain.LedgerEntry.Direction;
import com.sb24.api.domain.LedgerEntry.Kind;
import com.sb24.api.queue.NotificationService;
import com.sb24.api.queue.QueueService;
import com.sb24.api.queue.SettlementHandler;
import com.sb24.api.repo.*;
import com.sb24.api.util.Hashing;
import com.sb24.api.util.IbanUtil;
import com.sb24.api.web.Dtos.Receipt;
import com.sb24.api.web.Dtos.TransferRequest;
import com.sb24.api.web.Dtos.TransferStatusView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Same-bank transfers post to the ledger immediately. Interbank transfers are ASYNCHRONOUS: the money is reserved
 * ("cativo", available balance drops now, ledger balance unchanged) and a SETTLEMENT message is queued in the same
 * transaction; the worker later captures the hold (ledger entries) or releases it if the other bank rejects.
 * Authorisation: TRANSFER_CREATE (RBAC) + ownership, account state/type, new-beneficiary cool-off and trusted-device rules (ABAC).
 */
@Service
public class TransferService {
    static final ZoneId LUANDA = ZoneId.of("Africa/Luanda");

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final AccountHoldRepository holds;
    private final PendingTransferRepository pendings;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final LedgerService ledgerService;
    private final QueueService queue;
    private final NotificationService notifications;
    private final BeneficiaryService beneficiaries;
    private final PolicyService policy;
    private final AppProperties props;
    private final TransactionTemplate tx;

    public TransferService(AccountRepository accounts, LedgerRepository ledger, AccountHoldRepository holds,
                           PendingTransferRepository pendings, IdempotencyService idempotency, AuditService audit,
                           LedgerService ledgerService, QueueService queue, NotificationService notifications,
                           BeneficiaryService beneficiaries, PolicyService policy, AppProperties props, PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.holds = holds;
        this.pendings = pendings;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ledgerService = ledgerService;
        this.queue = queue;
        this.notifications = notifications;
        this.beneficiaries = beneficiaries;
        this.policy = policy;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    public IdempotencyService.Result<Receipt> transfer(UUID userId, TransferRequest r, String idemKey, String ip) {
        if ((r.toIban() == null) == (r.beneficiaryId() == null)) {
            throw ApiException.badRequest("DESTINATION_REQUIRED", "Indique exactamente um destino: toIban ou beneficiaryId.");
        }
        String hash = Hashing.sha256Hex(String.join("|", "T", r.fromAccountId().toString(), String.valueOf(r.toIban()), String.valueOf(r.beneficiaryId()),
                r.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(), String.valueOf(r.description())));
        try {
            IdempotencyService.Result<Receipt> result = tx.execute(status ->
                    idempotency.execute(userId, idemKey, hash, Receipt.class, () -> doTransfer(userId, r)));
            if (!result.replayed()) {
                audit.customer(userId, "TRANSFER", "SUCCESS", "TRANSFER", result.body().reference().toString(),
                        result.body().status() + " amount=" + result.body().amount());
            }
            return result;
        } catch (ApiException e) {
            audit.customer(userId, "TRANSFER", "DENIED", "ACCOUNT", r.fromAccountId().toString(), e.code());
            throw e;
        }
    }

    public TransferStatusView status(UUID userId, UUID ref) {
        PendingTransfer p = pendings.findById(ref).orElseThrow(() -> ApiException.notFound("Transferência não encontrada."));
        policy.require(Subject.customer(userId), Perm.TRANSFER_READ, Resource.of("TRANSFER", ref, p.getUserId(), Map.of()));
        return new TransferStatusView(p.getId(), p.getStatus().name(), p.getReason(), p.getAmount(), p.getFee(), p.getToIban(), p.getCreatedAt(), p.getSettledAt());
    }

    private Receipt doTransfer(UUID userId, TransferRequest r) {
        BigDecimal amount = r.amount().setScale(2, RoundingMode.UNNECESSARY);
        Beneficiary beneficiary = r.beneficiaryId() == null ? null : beneficiaries.loadForTransfer(userId, r.beneficiaryId());
        String toIban = beneficiary != null ? beneficiary.getIban() : r.toIban();
        if (!IbanUtil.isValid(toIban)) throw ApiException.unprocessable("INVALID_IBAN", "IBAN de destino inválido.");
        if (amount.compareTo(props.banking().perTransactionLimit()) > 0) {
            throw ApiException.unprocessable("LIMIT_EXCEEDED", "Excede o limite por operação.");
        }

        // Resolve ids WITHOUT loading entities, then lock all rows (ascending id) and only then read balances.
        boolean internal = IbanUtil.bankCode(toIban).equals(props.banking().bankCode());
        UUID destId = internal
                ? accounts.findIdByIban(toIban).orElseThrow(() -> ApiException.unprocessable("DESTINATION_NOT_FOUND", "Conta de destino não encontrada."))
                : accounts.findIdByPurpose("CLEARING").orElseThrow();
        if (destId.equals(r.fromAccountId())) throw ApiException.unprocessable("SAME_ACCOUNT", "A conta de origem e destino são iguais.");

        BigDecimal fee = internal ? BigDecimal.ZERO.setScale(2)
                : amount.multiply(props.banking().interbankFeeRate()).max(props.banking().interbankFeeMin()).setScale(2, RoundingMode.HALF_UP);

        List<Account> locked = accounts.lockAllById(Stream.of(r.fromAccountId(), destId).distinct().sorted().toList());
        Account src = find(locked, r.fromAccountId());
        if (src == null) throw ApiException.notFound("Conta de origem não encontrada.");
        Account dest = find(locked, destId);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("moneyOut", true);
        attrs.put("requiredType", "CURRENT");
        attrs.put("amount", amount);
        attrs.put("ownAccounts", internal && dest.getOwnerId().equals(userId));
        if (beneficiary != null) attrs.put("beneficiaryCreatedAt", beneficiary.getCreatedAt());
        policy.require(Subject.customer(userId), Perm.TRANSFER_CREATE, Resource.account(src, attrs)); // RBAC + ownership + state + cool-off + device

        if (!dest.isActive()) throw ApiException.unprocessable("ACCOUNT_BLOCKED", "Conta de destino inactiva.");
        if (internal && dest.getType() != AccountType.CURRENT) {
            throw ApiException.unprocessable("DESTINATION_NOT_FOUND", "Conta de destino não encontrada.");
        }

        BigDecimal total = amount.add(fee);
        Instant startOfDay = LocalDate.now(LUANDA).atStartOfDay(LUANDA).toInstant();
        if (ledger.debitedSince(src.getId(), startOfDay).add(src.getHeld()).add(total).compareTo(props.banking().dailyLimit()) > 0) {
            throw ApiException.unprocessable("DAILY_LIMIT_EXCEEDED", "Excede o limite diário.");
        }
        if (src.available().compareTo(total) < 0) { // only the AVAILABLE balance is spendable
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Saldo disponível insuficiente.");
        }
        if (beneficiary != null) beneficiaries.touch(beneficiary);

        UUID ref = UUID.randomUUID();
        if (internal) {
            src.setBalance(src.getBalance().subtract(amount));
            dest.setBalance(dest.getBalance().add(amount));
            ledgerService.append(src, ref, Kind.TRANSFER, Direction.DEBIT, amount, toIban, r.description());
            ledgerService.append(dest, ref, Kind.TRANSFER, Direction.CREDIT, amount, src.getIban(), r.description());
            notifications.notify(src.getOwnerId(), "Transferência enviada: " + amount + " AOA. Saldo disponível: " + src.available() + " AOA.");
            notifications.notify(dest.getOwnerId(), "Transferência recebida: " + amount + " AOA.");
            return new Receipt(ref, "COMPLETED", "TRANSFER", amount, fee, src.getIban(), toIban, src.getBalance(), src.available(), Instant.now());
        }

        // interbank: reserve now, settle asynchronously
        src.setHeld(src.getHeld().add(total));
        AccountHold hold = new AccountHold();
        hold.setAccountId(src.getId());
        hold.setRef(ref);
        hold.setAmount(total);
        holds.save(hold);
        PendingTransfer p = new PendingTransfer();
        p.setId(ref);
        p.setUserId(userId);
        p.setAccountId(src.getId());
        p.setToIban(toIban);
        p.setAmount(amount);
        p.setFee(fee);
        p.setDescription(r.description());
        pendings.save(p);
        queue.enqueue(QueueService.SETTLEMENT, new SettlementHandler.Payload(ref), "settle:" + ref);
        notifications.notify(userId, "Transferência de " + amount + " AOA em processamento. Valor cativo até à liquidação.");
        return new Receipt(ref, "PENDING_SETTLEMENT", "TRANSFER", amount, fee, src.getIban(), toIban, src.getBalance(), src.available(), Instant.now());
    }

    private static Account find(List<Account> list, UUID id) {
        return list.stream().filter(a -> a.getId().equals(id)).findFirst().orElse(null);
    }
}
