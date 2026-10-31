package com.company.resourceapi.services;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * At most one sync per project at a time, across all instances (a Redisson lock per project).
 *
 * <p>The lock is taken by the request and released by the background task, on another thread, so it
 * is held by a random owner id instead of a thread. It expires after the lease time, so an instance
 * that dies mid-sync doesn't block the project forever. If Redis can't be reached the sync runs
 * unlocked: a duplicate sync only costs an extra call, while refusing it would stop syncs entirely.
 */
@Component
@Slf4j
public class ProjectSyncLock {

    private final RedissonClient redisson;
    private final Duration leaseTime;

    public ProjectSyncLock(RedissonClient redisson, @Value("${resourceapi.sync.lock-lease-time}") Duration leaseTime) {
        this.redisson = redisson;
        this.leaseTime = leaseTime;
    }

    /** @return the lease to release when the sync ends, or empty if a sync of the project is running */
    public Optional<Lease> tryAcquire(long projectId) {
        var lock = lock(projectId);
        long ownerId = ThreadLocalRandom.current().nextLong();
        try {
            boolean acquired = lock.tryLockAsync(0, leaseTime.toMillis(), TimeUnit.MILLISECONDS, ownerId)
                    .toCompletableFuture().join();
            return acquired ? Optional.of(new Lease(lock, ownerId)) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Sync lock for project {} unavailable, syncing without it: {}", projectId, e.getMessage());
            return Optional.of(Lease.NONE);
        }
    }

    private RLock lock(long projectId) {
        return redisson.getLock("project-sync:" + projectId);
    }

    /** A held lock; releasing it twice, or after it expired, is harmless. */
    public record Lease(RLock lock, long ownerId) {

        static final Lease NONE = new Lease(null, 0);

        public void release() {
            if (lock == null) {
                return;
            }
            try {
                lock.unlockAsync(ownerId).toCompletableFuture().join();
            } catch (RuntimeException e) {
                // expired after its lease time (and maybe taken by another sync), or Redis unreachable
                log.warn("Sync lock {} was not released: {}", lock.getName(), e.getMessage());
            }
        }
    }
}
