package com.company.resourceapi.config;

import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One pooled Apache HttpClient for all {@code RestClient}s. Sizes the pool of the request factory
 * Boot creates (Boot's own settings such as spring.http.clients.* timeouts and SSL still apply),
 * and makes every {@link RestClient.Builder} use that single, Spring-managed factory.
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
                // HttpClient would otherwise wait up to 3 minutes for a connection from a full pool
                .withDefaultRequestConfigCustomizer(requestConfig -> requestConfig
                        .setConnectionRequestTimeout(Timeout.of(pool.connectionRequestTimeout())))
                .withHttpClientCustomizer(httpClient -> httpClient
                        .evictExpiredConnections()
                        .evictIdleConnections(TimeValue.of(pool.idleTimeout())));
    }

    /**
     * Replaces Boot's builder bean, which creates a new pooled HttpClient (with its own idle-evictor
     * thread, never closed) for every builder it hands out. These builders all use Boot's singleton
     * request factory instead: one pool, closed on shutdown. Boot's {@link RestClientCustomizer}s
     * (message converters, observation) are still applied.
     */
    @Bean
    @Scope("prototype")
    RestClient.Builder restClientBuilder(ClientHttpRequestFactory clientHttpRequestFactory,
            ObjectProvider<RestClientCustomizer> customizers) {
        var builder = RestClient.builder().requestFactory(clientHttpRequestFactory);
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder;
    }
}
