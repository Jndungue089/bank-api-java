package com.sb24.api.queue;

import com.sb24.api.domain.QueueMessage;
import com.sb24.api.service.ReconciliationService;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationHandler implements QueueHandler {
    public record Payload(LocalDate businessDate, String trigger) {}
    private final QueueService queue;
    private final ReconciliationService reconciliation;

    public ReconciliationHandler(QueueService queue, ReconciliationService reconciliation) {
        this.queue = queue;
        this.reconciliation = reconciliation;
    }

    @Override public String queue() { return QueueService.RECONCILIATION; }

    @Override
    public void handle(QueueMessage m) {
        Payload p = queue.read(m, Payload.class);
        reconciliation.run(p.businessDate(), p.trigger());
    }
}
