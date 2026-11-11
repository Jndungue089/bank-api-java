package com.sb24.api.service;

import com.sb24.api.access.Perm;
import com.sb24.api.access.PolicyService;
import com.sb24.api.access.Resource;
import com.sb24.api.access.Subject;
import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.*;
import com.sb24.api.domain.DirectDebitMandate.Status;
import com.sb24.api.domain.LedgerEntry.Direction;
import com.sb24.api.domain.LedgerEntry.Kind;
import com.sb24.api.queue.DirectDebitHandler;
import com.sb24.api.queue.NotificationService;
import com.sb24.api.queue.QueueService;
import com.sb24.api.repo.*;
import com.sb24.api.web.Dtos.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct debit (débito directo). The customer signs a MANDATE for a creditor (what, how much at most, how often).
 * The creditor, authenticated as a PARTNER, then submits COLLECTIONS against it; they are processed asynchronously
 * and are only paid if the mandate is active, within its ceiling and period, and the account has available funds.
 */
@Service
public class DirectDebitService {
    private final DirectDebitMandateRepository mandates;
    private final DirectDebitCollectionRepository collections;
    private final PaymentProviderRepository providers;
    private final PartnerRepository partners;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final PolicyService policy;
    private final AuditService audit;
    private final NotificationService notifications;
    private final QueueService queue;
    private final AppProperties props;

    public DirectDebitService(DirectDebitMandateRepository mandates, DirectDebitCollectionRepository collections, PaymentProviderRepository providers,
                              PartnerRepository partners, AccountRepository accounts, LedgerRepository ledger, LedgerService ledgerService,
                              PolicyService policy, AuditService audit, NotificationService notifications, QueueService queue, AppProperties props) {
        this.mandates = mandates;
        this.collections = collections;
        this.providers = providers;
        this.partners = partners;
        this.accounts = accounts;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.policy = policy;
        this.audit = audit;
        this.notifications = notifications;
        this.queue = queue;
        this.props = props;
    }

    // ---------------- customer side ----------------

    @Transactional
    public MandateView create(UUID userId, MandateRequest r) {
        Account acc = accounts.findById(r.accountId()).orElseThrow(() -> ApiException.notFound("Conta não encontrada."));
        policy.require(Subject.customer(userId), Perm.DIRECT_DEBIT_MANAGE, Resource.account(acc, Map.of("moneyOut", true, "requiredType", "CURRENT")));
        PaymentProvider provider = providers.findById(r.creditorCode().toUpperCase()).filter(p -> p.isEnabled() && p.isDirectDebit())
                .orElseThrow(() -> ApiException.unprocessable("CREDITOR_NOT_SUPPORTED", "Esta entidade não aceita débito directo."));
        if (!r.creditorReference().matches(provider.getReferencePattern())) {
            throw ApiException.unprocessable("INVALID_REFERENCE", "Referência inválida para " + provider.getName() + ".");
        }
        if (r.maxAmount().compareTo(props.banking().perTransactionLimit()) > 0) throw ApiException.unprocessable("LIMIT_EXCEEDED", "Excede o limite por operação.");
        if (mandates.findByCreditorCodeAndCreditorReference(provider.getCode(), r.creditorReference()).isPresent()) {
            throw ApiException.conflict("MANDATE_EXISTS", "Já existe um mandato para essa referência.");
        }
        DirectDebitMandate m = new DirectDebitMandate();
        m.setUserId(userId);
        m.setAccountId(acc.getId());
        m.setCreditorCode(provider.getCode());
        m.setCreditorReference(r.creditorReference());
        m.setMaxAmount(r.maxAmount().setScale(2));
        m.setFrequency(r.frequency());
        mandates.save(m);
        audit.customer(userId, "MANDATE_CREATE", "SUCCESS", "MANDATE", m.getId().toString(), "creditor=" + provider.getCode() + " max=" + m.getMaxAmount());
        notifications.notify(userId, "Mandato de débito directo para " + provider.getName() + " criado (máx. " + m.getMaxAmount() + " AOA, " + m.getFrequency() + ").");
        return view(m);
    }

    @Transactional(readOnly = true)
    public List<MandateView> list(UUID userId) {
        policy.requirePermission(Subject.customer(userId), Perm.DIRECT_DEBIT_READ);
        return mandates.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<CollectionView> collectionsOf(UUID userId, UUID mandateId) {
        DirectDebitMandate m = mandates.findById(mandateId).orElseThrow(() -> ApiException.notFound("Mandato não encontrado."));
        policy.require(Subject.customer(userId), Perm.DIRECT_DEBIT_READ, Resource.of("MANDATE", mandateId, m.getUserId(), Map.of()));
        return collections.findByMandateIdOrderByCreatedAtDesc(mandateId).stream().map(DirectDebitService::view).toList();
    }

    @Transactional
    public MandateView change(UUID userId, UUID mandateId, Status target) {
        DirectDebitMandate m = mandates.findById(mandateId).orElseThrow(() -> ApiException.notFound("Mandato não encontrado."));
        policy.require(Subject.customer(userId), Perm.DIRECT_DEBIT_MANAGE, Resource.of("MANDATE", mandateId, m.getUserId(), Map.of()));
        boolean ok = m.getStatus() != Status.CANCELLED && (target == Status.CANCELLED || (target == Status.PAUSED && m.getStatus() == Status.ACTIVE)
                || (target == Status.ACTIVE && m.getStatus() == Status.PAUSED));
        if (!ok) throw ApiException.unprocessable("INVALID_TRANSITION", "Transição inválida: " + m.getStatus() + " -> " + target + ".");
        m.setStatus(target);
        m.setUpdatedAt(Instant.now());
        audit.customer(userId, "MANDATE_" + target.name(), "SUCCESS", "MANDATE", mandateId.toString(), null);
        return view(m);
    }

    // ---------------- creditor (partner) side ----------------

    @Transactional
    public CollectionView submit(UUID partnerId, CollectionRequest r) {
        Subject me = Subject.partner(partnerId);
        policy.requirePermission(me, Perm.COLLECTION_SUBMIT);
        Partner partner = partners.findById(partnerId).orElseThrow(() -> ApiException.unauthorized("Parceiro desconhecido."));
        LocalDate today = LocalDate.now(TransferService.LUANDA);
        if (r.dueDate().isBefore(today.minusDays(1)) || r.dueDate().isAfter(today.plusDays(5))) {
            throw ApiException.unprocessable("INVALID_DUE_DATE", "A data de vencimento tem de estar entre ontem e daqui a 5 dias.");
        }
        // The lookup itself is scoped to the partner's own creditor code: other creditors' mandates simply do not exist for it.
        DirectDebitMandate m = mandates.findByCreditorCodeAndCreditorReference(partner.getCreditorCode(), r.creditorReference())
                .orElseThrow(() -> ApiException.notFound("Mandato não encontrado."));
        BigDecimal amount = r.amount().setScale(2);
        policy.require(me, Perm.COLLECTION_SUBMIT, Resource.of("MANDATE", m.getId(), null,
                Map.of("creditorCode", m.getCreditorCode(), "amount", amount, "mandateMax", m.getMaxAmount())));

        var existing = collections.findByPartnerIdAndExternalRef(partnerId, r.externalRef());
        if (existing.isPresent()) {
            DirectDebitCollection c = existing.get();
            if (!c.getMandateId().equals(m.getId()) || c.getAmount().compareTo(amount) != 0 || !c.getDueDate().equals(r.dueDate())) {
                throw ApiException.conflict("EXTERNAL_REF_REUSED", "externalRef já usado com outros dados.");
            }
            return view(c); // idempotent retry
        }
        DirectDebitCollection c = new DirectDebitCollection();
        c.setMandateId(m.getId());
        c.setPartnerId(partnerId);
        c.setExternalRef(r.externalRef());
        c.setAmount(amount);
        c.setDueDate(r.dueDate());
        collections.save(c);
        queue.enqueue(QueueService.DIRECT_DEBIT, new DirectDebitHandler.Payload(c.getId()), "dd:" + c.getId());
        notifications.notify(m.getUserId(), "Cobrança de débito directo de " + amount + " AOA de " + partner.getName() + " recebida para " + r.dueDate() + ".");
        audit.event(AuditLog.ActorType.PARTNER, partnerId, "COLLECTION_SUBMIT", "SUCCESS", "COLLECTION", c.getId().toString(), "amount=" + amount + " mandate=" + m.getId());
        return view(c);
    }

    @Transactional(readOnly = true)
    public CollectionView collection(UUID partnerId, UUID collectionId) {
        DirectDebitCollection c = collections.findById(collectionId).orElseThrow(() -> ApiException.notFound("Cobrança não encontrada."));
        policy.require(Subject.partner(partnerId), Perm.COLLECTION_READ, Resource.of("COLLECTION", collectionId, null, Map.of("partnerId", c.getPartnerId())));
        return view(c);
    }

    // ---------------- worker side ----------------

    @Transactional
    public void process(UUID collectionId) {
        DirectDebitCollection c = collections.findById(collectionId).orElse(null);
        if (c == null || c.getStatus() != DirectDebitCollection.Status.PENDING) return; // duplicate delivery
        DirectDebitMandate m = mandates.findById(c.getMandateId()).orElseThrow();
        if (m.getStatus() != Status.ACTIVE) { finish(c, m, DirectDebitCollection.Status.REJECTED, "MANDATE_" + m.getStatus()); return; }
        if (c.getAmount().compareTo(m.getMaxAmount()) > 0) { finish(c, m, DirectDebitCollection.Status.REJECTED, "EXCEEDS_MANDATE_LIMIT"); return; }
        LocalDate[] period = period(m.getFrequency(), c.getDueDate());
        if (collections.paidInPeriod(m.getId(), c.getId(), period[0], period[1]) > 0) { finish(c, m, DirectDebitCollection.Status.REJECTED, "PERIOD_ALREADY_COLLECTED"); return; }

        UUID settlementId = accounts.findIdByPurpose("PAYMENTS").orElseThrow();
        List<Account> locked = accounts.lockAllById(Stream.of(m.getAccountId(), settlementId).distinct().sorted().toList());
        Account acc = locked.stream().filter(a -> a.getId().equals(m.getAccountId())).findFirst().orElseThrow();
        Account settlement = locked.stream().filter(a -> a.getId().equals(settlementId)).findFirst().orElseThrow();
        if (!acc.isActive()) { finish(c, m, DirectDebitCollection.Status.RETURNED, "ACCOUNT_" + acc.getStatus()); return; }
        java.time.Instant startOfDay = LocalDate.now(TransferService.LUANDA).atStartOfDay(TransferService.LUANDA).toInstant();
        if (ledger.debitedSince(acc.getId(), startOfDay).add(acc.getHeld()).add(c.getAmount()).compareTo(props.banking().dailyLimit()) > 0) {
            finish(c, m, DirectDebitCollection.Status.RETURNED, "DAILY_LIMIT"); return;
        }
        if (acc.available().compareTo(c.getAmount()) < 0) { finish(c, m, DirectDebitCollection.Status.RETURNED, "INSUFFICIENT_FUNDS"); return; }

        UUID ref = UUID.randomUUID();
        acc.setBalance(acc.getBalance().subtract(c.getAmount()));
        settlement.setBalance(settlement.getBalance().add(c.getAmount()));
        String description = "Débito directo " + m.getCreditorCode() + " ref " + m.getCreditorReference();
        ledgerService.append(acc, ref, Kind.DIRECT_DEBIT, Direction.DEBIT, c.getAmount(), settlement.getIban(), description);
        ledgerService.append(settlement, ref, Kind.DIRECT_DEBIT, Direction.CREDIT, c.getAmount(), acc.getIban(), description);
        finish(c, m, DirectDebitCollection.Status.PAID, null);
    }

    private void finish(DirectDebitCollection c, DirectDebitMandate m, DirectDebitCollection.Status status, String reason) {
        c.setStatus(status);
        c.setReason(reason);
        c.setProcessedAt(Instant.now());
        String creditor = providers.findById(m.getCreditorCode()).map(PaymentProvider::getName).orElse(m.getCreditorCode());
        notifications.notify(m.getUserId(), status == DirectDebitCollection.Status.PAID
                ? "Débito directo de " + c.getAmount() + " AOA pago a " + creditor + "."
                : "Débito directo de " + c.getAmount() + " AOA de " + creditor + " não foi pago (" + reason + ").");
        audit.system("DIRECT_DEBIT", status == DirectDebitCollection.Status.PAID ? "SUCCESS" : status.name(), "COLLECTION", c.getId().toString(), reason);
    }

    /** [from, to) of the period the due date falls in. */
    static LocalDate[] period(DirectDebitMandate.Frequency f, LocalDate due) {
        return switch (f) {
            case WEEKLY -> new LocalDate[] {due.with(java.time.DayOfWeek.MONDAY), due.with(java.time.DayOfWeek.MONDAY).plusWeeks(1)};
            case MONTHLY -> new LocalDate[] {due.with(TemporalAdjusters.firstDayOfMonth()), due.with(TemporalAdjusters.firstDayOfNextMonth())};
            case ANNUAL -> new LocalDate[] {due.with(TemporalAdjusters.firstDayOfYear()), due.with(TemporalAdjusters.firstDayOfNextYear())};
        };
    }

    private MandateView view(DirectDebitMandate m) {
        String name = providers.findById(m.getCreditorCode()).map(PaymentProvider::getName).orElse(m.getCreditorCode());
        return new MandateView(m.getId(), m.getAccountId(), m.getCreditorCode(), name, m.getCreditorReference(), m.getMaxAmount(), m.getFrequency().name(), m.getStatus().name(), m.getCreatedAt());
    }

    static CollectionView view(DirectDebitCollection c) {
        return new CollectionView(c.getId(), c.getMandateId(), c.getExternalRef(), c.getAmount(), c.getDueDate(), c.getStatus().name(), c.getReason(), c.getCreatedAt(), c.getProcessedAt());
    }
}
