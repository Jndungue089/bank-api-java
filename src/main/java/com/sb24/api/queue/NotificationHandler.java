package com.sb24.api.queue;

import com.sb24.api.domain.Notification;
import com.sb24.api.domain.QueueMessage;
import com.sb24.api.repo.NotificationRepository;
import org.springframework.stereotype.Component;

/** Simulated delivery: persists the notification as SENT so customers can read it at GET /notifications. */
@Component
public class NotificationHandler implements QueueHandler {
    private final QueueService queue;
    private final NotificationRepository notifications;

    public NotificationHandler(QueueService queue, NotificationRepository notifications) {
        this.queue = queue;
        this.notifications = notifications;
    }

    @Override public String queue() { return QueueService.NOTIFICATION; }

    @Override
    public void handle(QueueMessage m) {
        NotificationService.Payload p = queue.read(m, NotificationService.Payload.class);
        Notification n = new Notification();
        n.setUserId(p.userId());
        n.setChannel(p.channel());
        n.setMessage(p.message());
        notifications.save(n);
    }
}
