package com.company.resourceapi.services;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * At most one sync per project at a time, across all instances (a Redisson lock per project).
 *
 * <p>The lock is taken by the request and released by the background task, on another thread, so it
 * is held by a random owner id instead of a thread. It expires after the lease time, so an instance
 * that dies mid-sync doesn't block the project forever. Without Redis (unreachable, or no Redisson
 * client for a non-standalone Redis) the sync runs unlocked: a duplicate sync only costs an extra
 * call, while refusing it would stop syncs entirely.
 */
@Component
@Slf4j
public class ProjectSyncLock {

    private final RedissonClient redisson;
    private final Duration leaseTime;

    public ProjectSyncLock(ObjectProvider<RedissonClient> redisson,
            @Value("${resourceapi.sync.lock-lease-time}") Duration leaseTime) {
        this.redisson = redisson.getIfAvailable();
        this.leaseTime = leaseTime;
        if (this.redisson == null) {
            log.warn("No Redisson client (only a standalone Redis is supported): syncs run without the per-project lock");
        }
    }

    /** @return the lease to release when the sync ends, or empty if a sync of the project is running */
    public Optional<Lease> tryAcquire(long projectId) {
        if (redisson == null) {
            return Optional.of(Lease.NONE);
        }
        var lock = redisson.getLock("project-sync:" + projectId);
        long ownerId = ThreadLocalRandom.current().nextLong();
        try {
            boolean acquired = lock.tryLockAsync(0, leaseTime.toMillis(), TimeUnit.MILLISECONDS, ownerId)
                    .toCompletableFuture().join();
            return acquired ? Optional.of(new Lease(lock, ownerId)) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Sync lock for project {} unavailable, syncing without it: {}", projectId, e.getMessage());
            // a timed-out request may still have taken the lock in Redis: keep the lease, so the sync's
            // end tries to release it instead of leaving it held until it expires
            return Optional.of(new Lease(lock, ownerId));
        }
    }

    /** A lock that may be held; releasing one that isn't (anymore) only logs. */
    public record Lease(RLock lock, long ownerId) {

        static final Lease NONE = new Lease(null, 0);

        public void release() {
            if (lock == null) {
                return;
            }
            try {
                lock.unlockAsync(ownerId).toCompletableFuture().join();
            } catch (RuntimeException e) {
                // not held: never taken, expired after its lease time, or Redis unreachable
                log.debug("Sync lock {} not released: {}", lock.getName(), e.getMessage());
            }
        }
    }
}
