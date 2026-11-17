package com.githublook.webhook;

import java.time.Instant;

/**
 * Message travelling through the Redis list. The raw payload is forwarded verbatim so the
 * consumer verifies nothing twice and re-parsing stays byte-exact.
 */
public record QueueMessage(
        String deliveryId,
        String eventName,
        String repoFullName,
        String payload,
        int attempt,
        Instant enqueuedAt) {

    public QueueMessage nextAttempt() {
        return new QueueMessage(deliveryId, eventName, repoFullName, payload, attempt + 1, enqueuedAt);
    }
}
