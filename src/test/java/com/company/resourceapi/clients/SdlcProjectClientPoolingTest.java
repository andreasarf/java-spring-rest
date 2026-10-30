package com.company.resourceapi.clients;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.resourceapi.entities.SdlcSystem;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.HttpComponentsClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.autoconfigure.ClientHttpRequestFactoryBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Counts the TCP connections the client opens to a WireMock SDLC system. */
@SpringBootTest(properties = "resourceapi.http-client.pool.max-per-route=2")
class SdlcProjectClientPoolingTest {

    private static final AtomicInteger openedConnections = new AtomicInteger();

    @RegisterExtension
    static WireMockExtension sdlcSystemServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    private SdlcProjectClient client;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private ClientHttpRequestFactory requestFactory;

    private SdlcSystem sdlcSystem;

    @TestConfiguration
    static class CountingConnectionsConfig {

        /** Applied after the application's pool customizer; only wraps how connections are created. */
        @Bean
        ClientHttpRequestFactoryBuilderCustomizer<HttpComponentsClientHttpRequestFactoryBuilder> countConnections() {
            return builder -> builder.withConnectionManagerCustomizer(connectionManager ->
                    connectionManager.setConnectionFactory(socket -> {
                        openedConnections.incrementAndGet();
                        return ManagedHttpClientConnectionFactory.INSTANCE.createConnection(socket);
                    }));
        }
    }

    @BeforeEach
    void setup() {
        // the client (and its pool) is shared by both tests
        sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystemServer.stubFor(get(urlPathMatching("/rest/api/2/project/.*"))
                .willReturn(okJson("{\"key\": \"POOLED\"}").withFixedDelay(200)));
    }

    @Test
    void givenSequentialRequestsThenOneConnectionIsReused() {
        int before = openedConnections.get();

        IntStream.range(0, 5).forEach(i -> client.fetchProject(sdlcSystem, "SEQ" + i));

        assertThat(openedConnections.get() - before).isLessThanOrEqualTo(1);
        // connections are counted at all, i.e. requests go through the pooled Apache HttpClient
        assertThat(openedConnections.get()).isPositive();
    }

    @Test
    void givenConcurrentRequestsThenConnectionsPerRouteAreCapped() {
        int before = openedConnections.get();

        // 6 parallel requests against a per-route limit of 2: the rest wait for a free connection.
        // Own threads, since the common pool may have just one worker on a small CI runner.
        try (var threads = Executors.newFixedThreadPool(6)) {
            var requests = IntStream.range(0, 6)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> client.fetchProject(sdlcSystem, "PAR" + i), threads))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(requests).join();
        }

        assertThat(openedConnections.get() - before).isBetween(1, 2);
        assertThat(sdlcSystemServer.getAllServeEvents()).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void givenClientsBuiltLikeTheApplicationOnesThenTheyShareOnePool() {
        // a second client built the same way must reuse the first client's kept-alive connection
        var otherClient = new SdlcProjectClient(restClientBuilder, requestFactory);
        client.fetchProject(sdlcSystem, "WARM");
        int before = openedConnections.get();

        otherClient.fetchProject(sdlcSystem, "SHARED");

        assertThat(openedConnections.get() - before).isZero();
    }
}
