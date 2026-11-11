package com.sb24.api.queue;

import com.sb24.api.domain.QueueMessage;
import com.sb24.api.service.InterestService;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class InterestHandler implements QueueHandler {
    public record Payload(LocalDate day) {}
    private final QueueService queue;
    private final InterestService interest;

    public InterestHandler(QueueService queue, InterestService interest) {
        this.queue = queue;
        this.interest = interest;
    }

    @Override public String queue() { return QueueService.INTEREST; }

    @Override public void handle(QueueMessage m) { interest.accrue(queue.read(m, Payload.class).day()); }
}
