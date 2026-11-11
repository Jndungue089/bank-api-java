package com.sb24.api.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sb24.api.config.AppProperties;
import com.sb24.api.domain.QueueMessage;
import com.sb24.api.repo.QueueMessageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Producer side of the durable queue. enqueue() joins the caller's transaction (transactional outbox): the message
 * exists if and only if the business change committed, so a crash can neither lose a notification/settlement nor
 * send one for a rolled-back transfer.
 */
@Service
public class QueueService {
    public static final String SETTLEMENT = "SETTLEMENT";
    public static final String NOTIFICATION = "NOTIFICATION";
    public static final String RECONCILIATION = "RECONCILIATION";
    public static final String INTEREST = "INTEREST";
    public static final String DIRECT_DEBIT = "DIRECT_DEBIT";

    private final QueueMessageRepository repo;
    private final ObjectMapper mapper;
    private final AppProperties props;

    public QueueService(QueueMessageRepository repo, ObjectMapper mapper, AppProperties props) {
        this.repo = repo;
        this.mapper = mapper;
        this.props = props;
    }

    @Transactional
    public UUID enqueue(String queue, Object payload, String dedupKey) {
        return enqueue(queue, payload, dedupKey, Duration.ZERO);
    }

    /** @param dedupKey optional idempotency key: a second message with the same key violates a unique constraint. */
    @Transactional
    public UUID enqueue(String queue, Object payload, String dedupKey, Duration delay) {
        QueueMessage m = new QueueMessage();
        m.setQueue(queue);
        try {
            m.setPayload(mapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload not serialisable", e);
        }
        m.setDedupKey(dedupKey);
        m.setMaxAttempts(props.queue().maxAttempts());
        m.setNextAttemptAt(Instant.now().plus(delay));
        return repo.saveAndFlush(m).getId();
    }

    public <T> T read(QueueMessage m, Class<T> type) {
        try {
            return mapper.readValue(m.getPayload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("bad payload", e);
        }
    }
}
