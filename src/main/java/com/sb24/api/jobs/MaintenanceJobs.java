package com.sb24.api.jobs;

import com.sb24.api.config.AppProperties;
import com.sb24.api.redis.JobLock;
import com.sb24.api.repo.IdempotencyRepository;
import com.sb24.api.repo.RefreshTokenRepository;
import com.sb24.api.service.AuditService;
import com.sb24.api.service.LedgerIntegrityService;
import com.sb24.api.queue.QueueService;
import com.sb24.api.queue.ReconciliationHandler;
import com.sb24.api.repo.QueueMessageRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Cron jobs (cron expressions in app.jobs.*, timezone Africa/Luanda). Each run takes a distributed lock first. */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class MaintenanceJobs {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);
    private final RefreshTokenRepository refreshTokens;
    private final IdempotencyRepository idempotency;
    private final LedgerIntegrityService integrity;
    private final AuditService audit;
    private final JobLock lock;
    private final AppProperties props;
    private final TransactionTemplate tx;
    private final QueueService queue;
    private final QueueMessageRepository queueMessages;

    public MaintenanceJobs(RefreshTokenRepository refreshTokens, IdempotencyRepository idempotency,
                           LedgerIntegrityService integrity, AuditService audit, JobLock lock, AppProperties props,
                           PlatformTransactionManager txManager, QueueService queue, QueueMessageRepository queueMessages) {
        this.queue = queue;
        this.queueMessages = queueMessages;
        this.tx = new TransactionTemplate(txManager);
        this.refreshTokens = refreshTokens;
        this.idempotency = idempotency;
        this.integrity = integrity;
        this.audit = audit;
        this.lock = lock;
        this.props = props;
    }

    /** Expired refresh tokens are useless and are credentials-at-rest: remove them. */
    @Scheduled(cron = "${app.jobs.purge-tokens-cron}", zone = "Africa/Luanda")
    public void purgeRefreshTokens() {
        lock.runExclusive("purge-refresh-tokens", Duration.ofMinutes(10), this::doPurgeRefreshTokens);
    }

    void doPurgeRefreshTokens() {
        int n = tx.execute(st -> refreshTokens.deleteExpired(Instant.now()));
        log.info("job purge-refresh-tokens removed={}", n);
    }

    /** Idempotency keys only need to outlive client retry windows. */
    @Scheduled(cron = "${app.jobs.purge-idempotency-cron}", zone = "Africa/Luanda")
    public void purgeIdempotencyRecords() {
        lock.runExclusive("purge-idempotency", Duration.ofMinutes(10), this::doPurgeIdempotency);
    }

    void doPurgeIdempotency() {
        int n = tx.execute(st -> idempotency.deleteOlderThan(Instant.now().minus(props.jobs().idempotencyRetention())));
        log.info("job purge-idempotency removed={}", n);
    }

    /**
     * 00:00 Africa/Luanda: closes the business day that just ended. The run is QUEUED (not executed inline) with a
     * dedup key per date, so with several instances exactly one reconciliation per day is created, and a failed run
     * is retried by the queue.
     */
    @Scheduled(cron = "${app.jobs.reconciliation-cron}", zone = "Africa/Luanda")
    public void scheduleDailyReconciliation() {
        enqueueReconciliation(LocalDate.now(ZoneId.of("Africa/Luanda")).minusDays(1), "CRON");
    }

    /** @return true if a new run was queued, false if one was already queued/completed for that date. */
    public boolean enqueueReconciliation(LocalDate day, String trigger) {
        String key = "recon:" + day + ("CRON".equals(trigger) ? "" : ":" + java.util.UUID.randomUUID().toString().substring(0, 8));
        if (queueMessages.existsByDedupKey(key)) return false;
        try {
            tx.executeWithoutResult(s -> queue.enqueue(QueueService.RECONCILIATION, new ReconciliationHandler.Payload(day, trigger), key));
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return false; // another instance won the race
        }
    }

    /** 00:15 Luanda: queue the daily savings interest accrual for the day that just closed (one per day, idempotent per account). */
    @Scheduled(cron = "${app.jobs.interest-cron}", zone = "Africa/Luanda")
    public void scheduleInterestAccrual() {
        enqueueInterest(LocalDate.now(ZoneId.of("Africa/Luanda")).minusDays(1));
    }

    public boolean enqueueInterest(LocalDate day) {
        String key = "interest:" + day;
        if (queueMessages.existsByDedupKey(key)) return false;
        try {
            tx.executeWithoutResult(s -> queue.enqueue(QueueService.INTEREST, new com.sb24.api.queue.InterestHandler.Payload(day), key));
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return false;
        }
    }

    /** Housekeeping for the queue table. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Africa/Luanda")
    public void purgeDoneMessages() {
        lock.runExclusive("purge-queue", Duration.ofMinutes(10), () -> {
            int n = tx.execute(st -> queueMessages.deleteDoneBefore(java.time.Instant.now().minus(Duration.ofDays(7))));
            log.info("job purge-queue removed={}", n);
        });
    }

    /** Nightly integrity audit of the hash-chained ledger and the zero-sum invariant. */
    @Scheduled(cron = "${app.jobs.ledger-integrity-cron}", zone = "Africa/Luanda")
    public void verifyLedger() {
        lock.runExclusive("ledger-integrity", Duration.ofMinutes(30), this::runIntegrityCheck);
    }

    public LedgerIntegrityService.Report runIntegrityCheck() {
        LedgerIntegrityService.Report report = integrity.verify();
        if (report.ok()) {
            log.info("job ledger-integrity OK accounts={} entries={}", report.accountsChecked(), report.entriesChecked());
            audit.record(null, "INTEGRITY_CHECK", "SUCCESS", null, "entries=" + report.entriesChecked());
        } else {
            log.error("job ledger-integrity VIOLATIONS: {}", report.violations());
            audit.record(null, "INTEGRITY_CHECK", "VIOLATION", null, String.join("; ", report.violations()));
        }
        return report;
    }
}
