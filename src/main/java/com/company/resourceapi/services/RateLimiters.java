package com.company.resourceapi.services;

import com.company.resourceapi.config.RateLimitProperties;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limiters shared by all instances (Redisson {@link RRateLimiter}), one per name.
 *
 * <p>A limiter is set up with the configured rate the first time this instance uses it (updating a
 * limiter created with another rate), and again if Redis dropped it after it expired from disuse.
 * Without Redis (unreachable, or no Redisson client) requests are allowed: limiting is a protection,
 * losing it shouldn't stop the application. After a failure Redis is left alone for a short while,
 * so an outage doesn't add a Redis timeout to every request.
 */
@Component
@Slf4j
public class RateLimiters {

    /** Unused limiters are removed from Redis after this many periods, but not sooner than MIN_KEEP_ALIVE. */
    private static final int KEEP_ALIVE_PERIODS = 10;
    private static final Duration MIN_KEEP_ALIVE = Duration.ofMinutes(1);
    /** How long requests are allowed without asking Redis after it failed. */
    static final Duration PAUSE_AFTER_FAILURE = Duration.ofSeconds(10);
    /** Limiters this instance has set up, most recently used kept (bounded: one per project). */
    private static final int MAX_REMEMBERED = 10_000;

    private final RedissonClient redisson;
    /** System.nanoTime() until which Redis isn't asked; 0 while it works. */
    private volatile long pausedUntil;
    private final Map<String, Boolean> setUp = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_REMEMBERED;
        }
    });

    public RateLimiters(ObjectProvider<RedissonClient> redisson) {
        this.redisson = redisson.getIfAvailable();
    }

    /** @return whether a permit was obtained, waiting at most the limit's max wait */
    public boolean tryAcquire(String name, RateLimitProperties.Limit limit) {
        if (redisson == null || isPaused()) {
            return true;
        }
        try {
            var limiter = redisson.getRateLimiter(name);
            if (!setUp.containsKey(name)) {
                setUp(name, limiter, limit);
            }
            try {
                return acquire(limiter, limit);
            } catch (RuntimeException e) {
                if (!isNotInitialized(e)) {
                    throw e;
                }
                // expired from Redis since this instance set it up
                setUp(name, limiter, limit);
                return acquire(limiter, limit);
            }
        } catch (RuntimeException e) {
            pausedUntil = System.nanoTime() + PAUSE_AFTER_FAILURE.toNanos();
            log.warn("Rate limiter {} unavailable, allowing requests without rate limits for {}: {}",
                    name, PAUSE_AFTER_FAILURE, e.getMessage());
            return true;
        }
    }

    private boolean isPaused() {
        long until = pausedUntil;
        if (until == 0) {
            return false;
        }
        if (System.nanoTime() - until < 0) {
            return true;
        }
        pausedUntil = 0;
        return false;
    }

    static Duration keepAlive(RateLimitProperties.Limit limit) {
        var periods = limit.period().multipliedBy(KEEP_ALIVE_PERIODS);
        return periods.compareTo(MIN_KEEP_ALIVE) > 0 ? periods : MIN_KEEP_ALIVE;
    }

    private static boolean acquire(RRateLimiter limiter, RateLimitProperties.Limit limit) {
        return limiter.tryAcquireAsync(1, limit.maxWait()).toCompletableFuture().join();
    }

    private void setUp(String name, RRateLimiter limiter, RateLimitProperties.Limit limit) {
        var keepAlive = keepAlive(limit);
        boolean created = limiter.trySetRateAsync(RateType.OVERALL, limit.permits(), limit.period(), keepAlive)
                .toCompletableFuture().join();
        if (!created) {
            // an existing limiter keeps its rate unless replaced, e.g. after the configuration changed
            var config = limiter.getConfigAsync().toCompletableFuture().join();
            if (config.getRate() != limit.permits() || config.getRateInterval() != limit.period().toMillis()) {
                log.info("Updating rate limiter {} to {} per {}", name, limit.permits(), limit.period());
                limiter.setRateAsync(RateType.OVERALL, limit.permits(), limit.period(), keepAlive)
                        .toCompletableFuture().join();
            }
        }
        // only once it is set up: a failure above is retried on the next call
        setUp.put(name, Boolean.TRUE);
    }

    private static boolean isNotInitialized(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("not initialized")) {
                return true;
            }
        }
        return false;
    }
}
