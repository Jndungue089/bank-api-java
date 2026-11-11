package com.sb24.api.queue;

import com.sb24.api.domain.QueueMessage;
import com.sb24.api.service.DirectDebitService;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DirectDebitHandler implements QueueHandler {
    public record Payload(UUID collectionId) {}
    private final QueueService queue;
    private final DirectDebitService directDebit;

    public DirectDebitHandler(QueueService queue, DirectDebitService directDebit) {
        this.queue = queue;
        this.directDebit = directDebit;
    }

    @Override public String queue() { return QueueService.DIRECT_DEBIT; }

    @Override public void handle(QueueMessage m) { directDebit.process(queue.read(m, Payload.class).collectionId()); }
}
