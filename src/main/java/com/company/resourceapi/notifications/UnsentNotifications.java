package com.company.resourceapi.notifications;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends again the events that weren't sent (RabbitMQ was down, the application stopped before
 * sending, ...). One instance does it at a time, chosen with a Redis lock; without Redis every
 * instance does it, which can only add duplicates, never lose an event.
 *
 * <p>The registry tracks every {@code @TransactionalEventListener}, so this also re-runs other
 * listeners that didn't finish, e.g. a sync after create that the application stopped before
 * starting.
 */
@Component
@Slf4j
public class UnsentNotifications {

    private static final String LOCK_NAME = "notifications-resubmit";

    private final IncompleteEventPublications incompletePublications;
    private final RedissonClient redisson;
    private final Duration resubmitAfter;

    public UnsentNotifications(IncompleteEventPublications incompletePublications,
            ObjectProvider<RedissonClient> redisson, NotificationsProperties properties) {
        this.incompletePublications = incompletePublications;
        this.redisson = redisson.getIfAvailable();
        this.resubmitAfter = properties.resubmitAfter();
    }

    @Scheduled(fixedDelayString = "${resourceapi.notifications.resubmit-interval:1m}",
            initialDelayString = "${resourceapi.notifications.resubmit-interval:1m}")
    public void resubmit() {
        var lock = redisson == null ? null : redisson.getLock(LOCK_NAME);
        boolean locked = false;
        if (lock != null) {
            try {
                // held at most as long as one round may take; another instance runs the next round
                locked = lock.tryLock(0, resubmitAfter.toMillis(), TimeUnit.MILLISECONDS);
                if (!locked) {
                    return;
                }
            } catch (Exception e) {
                log.warn("Resubmission lock unavailable, resubmitting anyway: {}", e.getMessage());
            }
        }
        try {
            incompletePublications.resubmitIncompletePublicationsOlderThan(resubmitAfter);
        } catch (RuntimeException e) {
            log.warn("Resubmitting unsent events failed: {}", e.getMessage());
        } finally {
            if (locked) {
                try {
                    lock.unlock();
                } catch (RuntimeException e) {
                    // expires with its lease
                    log.debug("Releasing the resubmission lock failed: {}", e.getMessage());
                }
            }
        }
    }
}
