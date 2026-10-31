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
 * losing it shouldn't stop the application.
 */
@Component
@Slf4j
public class RateLimiters {

    /** Unused limiters are removed from Redis after this. */
    private static final Duration KEEP_ALIVE = Duration.ofHours(1);
    /** Limiters this instance has set up, most recently used kept (bounded: one per project). */
    private static final int MAX_REMEMBERED = 10_000;

    private final RedissonClient redisson;
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
        if (redisson == null) {
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
            log.warn("Rate limiter {} unavailable, allowing the request: {}", name, e.getMessage());
            return true;
        }
    }

    private static boolean acquire(RRateLimiter limiter, RateLimitProperties.Limit limit) {
        return limiter.tryAcquireAsync(1, limit.maxWait()).toCompletableFuture().join();
    }

    private void setUp(String name, RRateLimiter limiter, RateLimitProperties.Limit limit) {
        var keepAlive = Duration.ofMillis(Math.max(KEEP_ALIVE.toMillis(), limit.period().toMillis()));
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
