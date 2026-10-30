package com.company.resourceapi.clients;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.resourceapi.entities.SdlcSystem;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.ResourceAccessException;

/** A request that can't get a pooled connection in time fails fast instead of queueing for minutes. */
@SpringBootTest(properties = {
        "resourceapi.http-client.pool.max-per-route=1",
        "resourceapi.http-client.pool.connection-request-timeout=200ms"})
class SdlcProjectClientPoolExhaustionTest {

    @RegisterExtension
    static WireMockExtension sdlcSystemServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    private SdlcProjectClient client;

    @Test
    void givenExhaustedPoolWhenFetchProjectThenFailAfterConnectionRequestTimeout() {
        // given: the single connection is busy for 2s with each request
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystemServer.stubFor(get(urlPathMatching("/rest/api/2/project/.*"))
                .willReturn(okJson("{\"key\": \"SLOW\"}").withFixedDelay(2_000)));

        // when: two requests released at the same moment
        var start = new CyclicBarrier(2);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var requests = IntStream.range(0, 2)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try {
                            start.await();
                            client.fetchProject(sdlcSystem, "SLOW" + i);
                            return "ok";
                        } catch (ResourceAccessException e) {
                            return "no connection";
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }, threads))
                    .toList();
            var outcomes = requests.stream().map(CompletableFuture::join).toList();

            // then: one is served, the other gives up waiting for the busy connection
            assertThat(outcomes).containsExactlyInAnyOrder("ok", "no connection");
        }
    }
}
