package com.sb24.api.queue;

import com.sb24.api.domain.QueueMessage;

/** One handler per queue. Runs inside a transaction together with the "mark DONE" update (effects and ack are atomic). */
public interface QueueHandler {
    String queue();
    void handle(QueueMessage message) throws Exception;
}
