package com.sb24.api.queue;

import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.QueueMessage;
import com.sb24.api.repo.QueueMessageRepository;
import com.sb24.api.service.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumer side. At-least-once delivery with: atomic claim (safe with several instances), lease expiry for crashed
 * workers, exponential backoff, and a dead-letter state after maxAttempts (visible and re-queueable by admins).
 * Handlers must therefore be idempotent.
 */
@Component
public class QueueWorker {
    private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);
    private final QueueMessageRepository repo;
    private final Map<String, QueueHandler> handlers = new HashMap<>();
    private final AuditService audit;
    private final AppProperties props;
    private final TransactionTemplate tx;
    private final String workerId = "w-" + UUID.randomUUID().toString().substring(0, 8);

    public QueueWorker(QueueMessageRepository repo, List<QueueHandler> handlerBeans, AuditService audit,
                       AppProperties props, PlatformTransactionManager txManager) {
        this.repo = repo;
        handlerBeans.forEach(h -> handlers.put(h.queue(), h));
        this.audit = audit;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${app.queue.poll-interval-ms:1000}")
    public void poll() {
        if (props.queue().enabled()) runOnce();
    }

    /** Processes one batch; returns how many messages were handled (success or failure). */
    public int runOnce() {
        Instant now = Instant.now();
        List<UUID> ready = repo.findReady(now, PageRequest.of(0, props.queue().batchSize()));
        int handled = 0;
        for (UUID id : ready) {
            Integer claimed = tx.execute(s -> repo.claim(id, workerId, Instant.now().plus(props.queue().lease()), Instant.now()));
            if (claimed == null || claimed != 1) continue; // another worker got it
            handled++;
            process(id);
        }
        return handled;
    }

    /** Test/ops helper: keep going until nothing is ready (retries with zero backoff included), bounded. */
    public int drain() {
        int total = 0;
        for (int i = 0; i < 50; i++) {
            int n = runOnce();
            if (n == 0) break;
            total += n;
        }
        return total;
    }

    private void process(UUID id) {
        QueueMessage snapshot = repo.findById(id).orElseThrow();
        QueueHandler handler = handlers.get(snapshot.getQueue());
        try {
            if (handler == null) throw new IllegalStateException("no handler for queue " + snapshot.getQueue());
            tx.executeWithoutResult(s -> {
                QueueMessage m = repo.findById(id).orElseThrow();
                try {
                    handler.handle(m);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                m.setStatus(QueueMessage.Status.DONE);
                m.setProcessedAt(Instant.now());
                m.setLockedBy(null);
                m.setLastError(null);
                repo.save(m);
            });
        } catch (RuntimeException e) {
            fail(id, e);
        }
    }

    private void fail(UUID id, RuntimeException e) {
        String error = (e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())).replaceAll("[\\p{Cntrl}]", " ");
        error = error.substring(0, Math.min(error.length(), 300));
        final String err = error;
        tx.executeWithoutResult(s -> {
            QueueMessage m = repo.findById(id).orElseThrow();
            m.setLastError(err);
            m.setLockedBy(null);
            if (m.getAttempts() >= m.getMaxAttempts()) {
                m.setStatus(QueueMessage.Status.DEAD);
                m.setProcessedAt(Instant.now());
                audit.system("QUEUE_DEAD_LETTER", "FAILED", "QUEUE_MESSAGE", id.toString(), m.getQueue() + ": " + err);
                log.error("queue message {} ({}) moved to dead letter after {} attempts: {}", id, m.getQueue(), m.getAttempts(), err);
            } else {
                long backoff = Math.min(Duration.ofMinutes(5).toMillis(), props.queue().backoffBaseMs() * (1L << (m.getAttempts() - 1)));
                m.setStatus(QueueMessage.Status.PENDING);
                m.setNextAttemptAt(Instant.now().plusMillis(backoff));
                log.warn("queue message {} ({}) failed attempt {}, retry in {} ms: {}", id, m.getQueue(), m.getAttempts(), backoff, err);
            }
            repo.save(m);
        });
    }
}
