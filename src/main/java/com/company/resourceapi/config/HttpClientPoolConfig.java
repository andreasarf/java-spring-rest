package com.company.resourceapi.config;

import org.apache.hc.core5.util.TimeValue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sizes the Apache HttpClient connection pool that Boot creates for {@code RestClient}s built from
 * the auto-configured builder. Boot's own settings (spring.http.clients.* timeouts, SSL) still
 * apply, since this only customizes the builder Boot uses.
 */
@Configuration
@EnableConfigurationProperties(HttpClientPoolProperties.class)
public class HttpClientPoolConfig {

    @Bean
    ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> connectionPoolCustomizer(
            HttpClientPoolProperties pool) {
        return builder -> builder
                .withConnectionManagerCustomizer(connectionManager -> connectionManager
                        .setMaxConnTotal(pool.maxTotal())
                        .setMaxConnPerRoute(pool.maxPerRoute()))
                // Adds to Boot's connection config (which carries the connect/read timeouts) instead
                // of replacing it, as the pool builder's setConnectionTimeToLive would
                .withConnectionConfigCustomizer(connectionConfig -> connectionConfig
                        .setTimeToLive(TimeValue.of(pool.timeToLive())))
                .withHttpClientCustomizer(httpClient -> httpClient
                        .evictExpiredConnections()
                        .evictIdleConnections(TimeValue.of(pool.idleTimeout())));
    }
}
