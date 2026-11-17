package com.githublook.webhook;

import com.githublook.common.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Redis List consumers, one per configured worker, each on its own virtual thread.
 *
 * <p>Virtual threads are the right tool here: the loop spends most of its life parked waiting
 * for the next message, and a platform thread per worker would waste a megabyte of stack while
 * doing it. Failures are retried up to {@code app.queue.max-attempts} and then parked in the
 * dead-letter list instead of blocking the queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookConsumer implements SmartLifecycle {

    private final WebhookQueue queue;
    private final WebhookEventProcessor processor;
    private final DeliveryAuditService audit;
    private final AppProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final List<Thread> workers = new ArrayList<>();

    @Override
    public void start() {
        int count = props.getQueue().getConsumers();
        running.set(true);
        for (int i = 0; i < count; i++) {
            Thread worker = Thread.ofVirtual()
                    .name("webhook-consumer-" + i)
                    .start(this::loop);
            workers.add(worker);
        }
        log.info("queue_consumers_started count={} queue={}", count, props.getQueue().getKey());
    }

    @Override
    public void stop() {
        running.set(false);
        workers.forEach(Thread::interrupt);
        workers.clear();
        log.info("queue_consumers_stopped processed={} failed={}", processed.get(), failed.get());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        long parkNanos = props.getQueue().getPollInterval().toNanos();
        while (running.get()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                Optional<QueueMessage> message = queue.poll();
                if (message.isPresent()) {
                    dispatch(message.get());
                } else {
                    // Idle: park a short while instead of spinning, keeps Redis QPS low.
                    LockSupport.parkNanos(parkNanos);
                }
            } catch (Exception e) {
                log.error("consumer_loop_error", e);
                LockSupport.parkNanos(parkNanos * 20);
            }
        }
    }

    private void dispatch(QueueMessage message) {
        try {
            processor.process(message);
            processed.incrementAndGet();
        } catch (PermanentProcessingException e) {
            failed.incrementAndGet();
            log.error("event_permanently_failed deliveryId={} event={} reason={}",
                    message.deliveryId(), message.eventName(), e.getMessage());
            queue.deadLetter(message, e.getMessage());
            audit.markFailed(message.deliveryId(), e.getMessage());
        } catch (Exception e) {
            failed.incrementAndGet();
            int maxAttempts = props.getQueue().getMaxAttempts();
            if (message.attempt() + 1 >= maxAttempts) {
                log.error("event_failed_giving_up deliveryId={} event={} attempts={}",
                        message.deliveryId(), message.eventName(), message.attempt() + 1, e);
                queue.deadLetter(message, e.getClass().getSimpleName() + ": " + e.getMessage());
                audit.markFailed(message.deliveryId(), e.getMessage());
            } else {
                log.warn("event_failed_retrying deliveryId={} event={} attempt={} reason={}",
                        message.deliveryId(), message.eventName(), message.attempt() + 1, e.toString());
                queue.enqueue(message.nextAttempt());
            }
        }
    }

    public long processedCount() {
        return processed.get();
    }

    public long failedCount() {
        return failed.get();
    }
}
