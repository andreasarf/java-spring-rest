package com.company.resourceapi.services;

import com.company.resourceapi.config.RateLimitProperties;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiters shared by all instances (Redisson {@link RRateLimiter}), one per name.
 *
 * <p>A limiter is created with the configured rate on first use, and updated when the configuration
 * changed; its state expires after a while unused. Without Redis (unreachable, or no Redisson
 * client) requests are allowed: limiting is a protection, losing it shouldn't stop the application.
 */
@Component
@Slf4j
public class RateLimiters {

    /** Unused limiters are removed from Redis after this. */
    private static final Duration KEEP_ALIVE = Duration.ofHours(1);

    private final RedissonClient redisson;
    private final Set<String> checkedConfigs = ConcurrentHashMap.newKeySet();

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
            ensureRate(name, limiter, limit);
            return limiter.tryAcquireAsync(1, limit.maxWait()).toCompletableFuture().join();
        } catch (RuntimeException e) {
            log.warn("Rate limiter {} unavailable, allowing the request: {}", name, e.getMessage());
            return true;
        }
    }

    private void ensureRate(String name, RRateLimiter limiter, RateLimitProperties.Limit limit) {
        long keepAliveMillis = Math.max(KEEP_ALIVE.toMillis(), limit.period().toMillis());
        boolean created = limiter.trySetRateAsync(RateType.OVERALL, limit.permits(), limit.period(),
                Duration.ofMillis(keepAliveMillis)).toCompletableFuture().join();
        // once per instance and limiter: an existing limiter keeps its old rate unless it is replaced
        if (!created && checkedConfigs.add(name)) {
            var config = limiter.getConfigAsync().toCompletableFuture().join();
            if (config.getRate() != limit.permits() || config.getRateInterval() != limit.period().toMillis()) {
                log.info("Updating rate limiter {} to {} per {}", name, limit.permits(), limit.period());
                limiter.setRateAsync(RateType.OVERALL, limit.permits(), limit.period(), Duration.ofMillis(keepAliveMillis))
                        .toCompletableFuture().join();
            }
        }
    }
}
