package com.company.resourceapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Distributed rate limits ({@code resourceapi.rate-limit.*}), shared by all instances through Redis.
 *
 * @param sdlc calls to one SDLC system (protects its API quota)
 * @param sync sync requests for one project
 */
@ConfigurationProperties("resourceapi.rate-limit")
public record RateLimitProperties(Limit sdlc, Limit sync) {

    /**
     * @param permits how many per period
     * @param period  the period the permits refill over
     * @param maxWait how long a caller may wait for a permit before being refused
     */
    public record Limit(long permits, Duration period, Duration maxWait) {

        /** Roughly when the next permit is available, for a Retry-After header. */
        public long retryAfterSeconds() {
            return Math.max(1, (long) Math.ceil(period.toMillis() / 1000.0 / permits));
        }
    }
}
