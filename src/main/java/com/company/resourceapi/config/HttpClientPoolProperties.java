package com.company.resourceapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection pool of the HTTP client behind {@code RestClient} ({@code resourceapi.http-client.pool.*}).
 *
 * @param maxTotal                 maximum open connections across all SDLC systems
 * @param maxPerRoute              maximum open connections to a single SDLC system (host)
 * @param connectionRequestTimeout how long a request waits for a free connection when the pool
 *                                 is exhausted, before failing
 * @param idleTimeout              idle connections are closed after this long
 * @param timeToLive               connections are never reused after this long, e.g. to pick up
 *                                 DNS changes
 */
@ConfigurationProperties("resourceapi.http-client.pool")
public record HttpClientPoolProperties(
        @DefaultValue("50") int maxTotal,
        @DefaultValue("10") int maxPerRoute,
        @DefaultValue("2s") Duration connectionRequestTimeout,
        @DefaultValue("30s") Duration idleTimeout,
        @DefaultValue("5m") Duration timeToLive) {
}
