package com.company.resourceapi.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Distributed rate limits ({@code resourceapi.rate-limit.*}), shared by all instances through Redis.
 * Validated at startup: a broken limit would otherwise fail, or silently stop limiting, at runtime.
 *
 * @param sdlc calls to one SDLC system (protects its API quota)
 * @param sync sync requests for one project
 */
@Validated
@ConfigurationProperties("resourceapi.rate-limit")
public record RateLimitProperties(@NotNull @Valid Limit sdlc, @NotNull @Valid Limit sync) {

    /**
     * At most {@code permits} per sliding window of {@code period}: each permit becomes available again
     * one period after it was used.
     *
     * @param permits how many per period
     * @param period  the sliding window
     * @param maxWait how long a caller may wait for a permit before being refused
     */
    public record Limit(@Positive long permits, @NotNull Duration period, @NotNull Duration maxWait) {

        /** When a permit is surely available again: a used permit only comes back after a full period. */
        public long retryAfterSeconds() {
            return Math.max(1, (long) Math.ceil(period.toMillis() / 1000.0));
        }
    }
}
