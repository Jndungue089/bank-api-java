package com.sb24.api.queue;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Enqueues customer notifications (SMS/e-mail/push in a real bank). Delivery happens asynchronously in the worker. */
@Service
public class NotificationService {
    public record Payload(UUID userId, String channel, String message) {}
    private final QueueService queue;

    public NotificationService(QueueService queue) { this.queue = queue; }

    public void notify(UUID userId, String message) {
        queue.enqueue(QueueService.NOTIFICATION, new Payload(userId, "SMS", message), null);
    }
}
