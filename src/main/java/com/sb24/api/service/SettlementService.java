package com.sb24.api.service;

import com.sb24.api.domain.*;
import com.sb24.api.domain.LedgerEntry.Direction;
import com.sb24.api.domain.LedgerEntry.Kind;
import com.sb24.api.queue.NotificationService;
import com.sb24.api.repo.AccountHoldRepository;
import com.sb24.api.repo.AccountRepository;
import com.sb24.api.repo.PendingTransferRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Worker-side half of an interbank transfer. Idempotent: a message delivered twice settles once. */
@Service
public class SettlementService {
    private final PendingTransferRepository pendings;
    private final AccountHoldRepository holds;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final InterbankGateway gateway;
    private final NotificationService notifications;
    private final AuditService audit;

    public SettlementService(PendingTransferRepository pendings, AccountHoldRepository holds, AccountRepository accounts,
                             LedgerService ledgerService, InterbankGateway gateway, NotificationService notifications, AuditService audit) {
        this.pendings = pendings;
        this.holds = holds;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.gateway = gateway;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public void settle(UUID pendingId, int attempt) {
        PendingTransfer p = pendings.findById(pendingId).orElse(null);
        if (p == null || p.getStatus() != PendingTransfer.Status.PENDING) return; // already handled (duplicate delivery)

        InterbankGateway.Decision decision = gateway.submit(p, attempt); // TransientGatewayException => rollback => queue retries

        UUID clearingId = accounts.findIdByPurpose("CLEARING").orElseThrow();
        UUID feesId = accounts.findIdByPurpose("FEES").orElseThrow();
        List<Account> locked = accounts.lockAllById(Stream.of(p.getAccountId(), clearingId, feesId).distinct().sorted().toList());
        Account src = locked.stream().filter(a -> a.getId().equals(p.getAccountId())).findFirst().orElseThrow();
        AccountHold hold = holds.findByRefAndStatus(p.getId(), AccountHold.Status.ACTIVE).orElseThrow();
        BigDecimal total = p.getAmount().add(p.getFee());
        Instant now = Instant.now();

        if (decision.accepted()) {
            Account clearing = locked.stream().filter(a -> a.getId().equals(clearingId)).findFirst().orElseThrow();
            Account fees = locked.stream().filter(a -> a.getId().equals(feesId)).findFirst().orElseThrow();
            src.setHeld(src.getHeld().subtract(hold.getAmount()));
            src.setBalance(src.getBalance().subtract(p.getAmount()));
            clearing.setBalance(clearing.getBalance().add(p.getAmount()));
            ledgerService.append(src, p.getId(), Kind.TRANSFER, Direction.DEBIT, p.getAmount(), p.getToIban(), p.getDescription());
            ledgerService.append(clearing, p.getId(), Kind.TRANSFER, Direction.CREDIT, p.getAmount(), src.getIban(), p.getDescription());
            if (p.getFee().signum() > 0) {
                src.setBalance(src.getBalance().subtract(p.getFee()));
                fees.setBalance(fees.getBalance().add(p.getFee()));
                ledgerService.append(src, p.getId(), Kind.FEE, Direction.DEBIT, p.getFee(), fees.getIban(), "Comissão de transferência interbancária");
                ledgerService.append(fees, p.getId(), Kind.FEE, Direction.CREDIT, p.getFee(), src.getIban(), "Comissão");
            }
            hold.setStatus(AccountHold.Status.CAPTURED);
            p.setStatus(PendingTransfer.Status.SETTLED);
            notifications.notify(p.getUserId(), "Transferência de " + p.getAmount() + " AOA concluída.");
            audit.system("TRANSFER_SETTLED", "SUCCESS", "TRANSFER", p.getId().toString(), "amount=" + p.getAmount() + " fee=" + p.getFee());
        } else {
            src.setHeld(src.getHeld().subtract(hold.getAmount()));
            hold.setStatus(AccountHold.Status.RELEASED);
            p.setStatus(PendingTransfer.Status.REJECTED);
            p.setReason(decision.reason());
            notifications.notify(p.getUserId(), "Transferência de " + p.getAmount() + " AOA rejeitada (" + decision.reason() + "). O valor cativo foi libertado.");
            audit.system("TRANSFER_SETTLED", "REJECTED", "TRANSFER", p.getId().toString(), decision.reason());
        }
        hold.setClosedAt(now);
        p.setSettledAt(now);
    }
}
