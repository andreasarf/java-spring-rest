package com.company.resourceapi.services;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.config.ProjectSyncQueueConfig.SyncQueueProperties;
import com.company.resourceapi.controllers.ProjectRestController;
import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Syncs are queued in RabbitMQ, retried when the SDLC system fails, and dead-lettered if it keeps failing. */
@SpringBootTest(properties = {
        // one consumer, so requests have to wait for it
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1"})
@AutoConfigureMockMvc
class ProjectSyncQueueTest {

    @RegisterExtension
    static WireMockExtension sdlcSystemServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private SyncQueueProperties syncQueue;

    private SdlcSystem sdlcSystem;

    @BeforeEach
    void setup() {
        sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        amqpAdmin.purgeQueue(syncQueue.deadLetterQueue(), false);
    }

    @Test
    void givenMoreRequestsThanConsumersThenAllAreAcceptedAndSynced() throws Exception {
        sdlcSystemServer.stubFor(get(urlPathMatching("/rest/api/2/project/QUEUED-.*"))
                .willReturn(okJson("{\"key\": \"Q\", \"name\": \"Synced\"}").withFixedDelay(200)));
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            ids.add(project("QUEUED-" + i));
        }

        // nothing refused for lack of a free slot: the requests wait in the queue
        for (long id : ids) {
            mockMvc.perform(post(syncUrl(id))).andExpect(status().isAccepted());
        }

        await(() -> ids.stream().allMatch(id -> "Synced".equals(nameOf(id))), Duration.ofSeconds(10));
    }

    @Test
    void givenSdlcSystemFailingOnceThenSyncSucceedsOnRetry() throws Exception {
        var url = "/rest/api/2/project/FLAKY";
        sdlcSystemServer.stubFor(get(urlEqualTo(url)).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        sdlcSystemServer.stubFor(get(urlEqualTo(url)).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"key\": \"FLAKY\", \"name\": \"After retry\"}")));
        long id = project("FLAKY");

        mockMvc.perform(post(syncUrl(id))).andExpect(status().isAccepted());

        await(() -> "After retry".equals(nameOf(id)), Duration.ofSeconds(5));
        sdlcSystemServer.verify(2, getRequestedFor(urlEqualTo(url)));
    }

    @Test
    void givenSdlcSystemKeepsFailingThenRequestIsDeadLetteredAndLockReleased() throws Exception {
        var url = "/rest/api/2/project/BROKEN";
        sdlcSystemServer.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(500)));
        long id = project("BROKEN");

        mockMvc.perform(post(syncUrl(id))).andExpect(status().isAccepted());

        // the first try and 2 retries, then kept for inspection
        assertThat(body(receiveDeadLetter())).contains("\"projectId\":" + id);
        sdlcSystemServer.verify(3, getRequestedFor(urlEqualTo(url)));
        // the lock was released: a new sync can be requested right away
        await(() -> statusOfSync(id) == 202, Duration.ofSeconds(2));
    }

    @Test
    void givenSdlcSystemRejectingTheRequestThenItIsNotRetried() throws Exception {
        var url = "/rest/api/2/project/FORBIDDEN";
        sdlcSystemServer.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(403)));
        long id = project("FORBIDDEN");

        mockMvc.perform(post(syncUrl(id))).andExpect(status().isAccepted());

        assertThat(body(receiveDeadLetter())).contains("\"projectId\":" + id);
        sdlcSystemServer.verify(1, getRequestedFor(urlEqualTo(url)));
    }

    private Message receiveDeadLetter() {
        var deadLetter = rabbitTemplate.receive(syncQueue.deadLetterQueue(), 5_000);
        assertThat(deadLetter).as("dead-lettered within 5s").isNotNull();
        return deadLetter;
    }

    private static String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    private long project(String externalId) {
        var project = new Project();
        project.setExternalId(externalId);
        project.setName("Old name");
        project.setSdlcSystem(sdlcSystem);
        return projectRepository.save(project).getId();
    }

    private int statusOfSync(long id) {
        try {
            return mockMvc.perform(post(syncUrl(id))).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String nameOf(long id) {
        return projectRepository.findById(id).orElseThrow().getName();
    }

    private static String syncUrl(long id) {
        return ProjectRestController.ENDPOINT + "/" + id + "/sync";
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met within " + timeout).isBefore(deadline);
            Thread.sleep(50);
        }
    }
}
