package com.sb24.api.queue;

import com.sb24.api.domain.QueueMessage;
import com.sb24.api.service.SettlementService;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SettlementHandler implements QueueHandler {
    public record Payload(UUID pendingTransferId) {}
    private final QueueService queue;
    private final SettlementService settlement;

    public SettlementHandler(QueueService queue, SettlementService settlement) {
        this.queue = queue;
        this.settlement = settlement;
    }

    @Override public String queue() { return QueueService.SETTLEMENT; }

    @Override
    public void handle(QueueMessage m) {
        settlement.settle(queue.read(m, Payload.class).pendingTransferId(), m.getAttempts());
    }
}
