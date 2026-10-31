package com.company.resourceapi.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.controllers.ProjectRestController;
import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Distributed rate limits against a real Redis; "another instance" uses the same limiter directly. */
@SpringBootTest(properties = {
        "resourceapi.rate-limit.sdlc.permits=2",
        "resourceapi.rate-limit.sdlc.period=2s",
        "resourceapi.rate-limit.sdlc.max-wait=0s",
        "resourceapi.rate-limit.sync.permits=2",
        "resourceapi.rate-limit.sync.period=1m"})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

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
    private RedissonClient redisson;

    private SdlcSystem sdlcSystem;

    @BeforeEach
    void setup() {
        sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        sdlcSystemServer.stubFor(WireMock.get(urlPathMatching("/rest/api/2/project/.*"))
                .willReturn(okJson("{\"key\": \"ANY\", \"name\": \"Synced\"}")));
    }

    @Test
    void givenTooManyCallsToOneSdlcSystemThenFurtherCallsAreRefused() throws Exception {
        mockMvc.perform(get(externalUrl(project("A")))).andExpect(status().isOk());
        mockMvc.perform(get(externalUrl(project("B")))).andExpect(status().isOk());
        mockMvc.perform(get(externalUrl(project("C"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "2"));

        sdlcSystemServer.verify(2, getRequestedFor(urlPathMatching("/rest/api/2/project/.*")));
    }

    @Test
    void givenPermitsUsedByAnotherInstanceThenThisInstanceIsLimitedToo() throws Exception {
        var shared = redisson.getRateLimiter("sdlc-rate:" + sdlcSystem.getId());
        shared.trySetRate(RateType.OVERALL, 2, Duration.ofSeconds(2), Duration.ofHours(1));
        assertThat(shared.tryAcquire(2)).isTrue();

        mockMvc.perform(get(externalUrl(project("A")))).andExpect(status().isServiceUnavailable());

        // the permits refill after the period
        var url = externalUrl(project("B"));
        await(() -> statusOf(url) == 200, Duration.ofSeconds(5));
    }

    @Test
    void givenCachedAnswersThenOnlyRealCallsCount() throws Exception {
        var url = externalUrl(project("A"));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(url)).andExpect(status().isOk());
        }

        sdlcSystemServer.verify(1, getRequestedFor(urlPathMatching("/rest/api/2/project/.*")));
        mockMvc.perform(get(externalUrl(project("B")))).andExpect(status().isOk());
    }

    @Test
    void givenChangedConfigurationThenExistingLimiterIsUpdated() throws Exception {
        var limiter = redisson.getRateLimiter("sdlc-rate:" + sdlcSystem.getId());
        limiter.trySetRate(RateType.OVERALL, 100, Duration.ofSeconds(1), Duration.ofHours(1));

        mockMvc.perform(get(externalUrl(project("A")))).andExpect(status().isOk());

        var config = limiter.getConfig();
        assertThat(config.getRate()).isEqualTo(2);
        assertThat(config.getRateInterval()).isEqualTo(Duration.ofSeconds(2).toMillis());
    }

    @Test
    void givenTooManySyncRequestsForOneProjectThenTheyAreRefused() throws Exception {
        long projectId = project("SYNCED");
        var syncUrl = ProjectRestController.ENDPOINT + "/" + projectId + "/sync";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
            // let it finish, so the lock doesn't refuse the next one
            await(() -> !redisson.getLock("project-sync:" + projectId).isLocked(), Duration.ofSeconds(5));
        }
        mockMvc.perform(post(syncUrl))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    private long project(String externalId) {
        var project = new Project();
        project.setExternalId(externalId);
        project.setSdlcSystem(sdlcSystem);
        return projectRepository.save(project).getId();
    }

    private static String externalUrl(long projectId) {
        return ProjectRestController.ENDPOINT + "/" + projectId + "/external";
    }

    private int statusOf(String url) {
        try {
            return mockMvc.perform(get(url)).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met within " + timeout).isBefore(deadline);
            Thread.sleep(100);
        }
    }

    @Test
    void givenSyncAlreadyRunningThenRefusedRequestDoesNotUseAPermit() throws Exception {
        long projectId = project("BUSY");
        var syncUrl = ProjectRestController.ENDPOINT + "/" + projectId + "/sync";
        var otherInstanceLock = redisson.getLock("project-sync:" + projectId);
        otherInstanceLock.tryLockAsync(0, 10, TimeUnit.SECONDS, 4714L).toCompletableFuture().join();
        try {
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post(syncUrl)).andExpect(status().isConflict());
            }
        } finally {
            otherInstanceLock.unlockAsync(4714L).toCompletableFuture().join();
        }

        // both permits are still there
        mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
        // let that sync finish here, not during the next test
        await(() -> !otherInstanceLock.isLocked(), Duration.ofSeconds(5));
    }

    @Test
    void givenLimiterThenItExpiresFromRedisSoonAfterItsLastUse() throws Exception {
        mockMvc.perform(get(externalUrl(project("A")))).andExpect(status().isOk());

        // 10 periods of 2s is less than the 1-minute minimum
        long ttl = redisson.getRateLimiter("sdlc-rate:" + sdlcSystem.getId()).remainTimeToLive();
        assertThat(ttl).isBetween(Duration.ofSeconds(50).toMillis(), Duration.ofMinutes(1).toMillis());
    }

    @Test
    void givenLimiterRemovedFromRedisThenItIsSetUpAgain() throws Exception {
        mockMvc.perform(get(externalUrl(project("A")))).andExpect(status().isOk());
        var limiter = redisson.getRateLimiter("sdlc-rate:" + sdlcSystem.getId());
        // expired from disuse (or flushed)
        assertThat(limiter.delete()).isTrue();

        mockMvc.perform(get(externalUrl(project("B")))).andExpect(status().isOk());

        assertThat(limiter.getConfig().getRate()).isEqualTo(2);
        // a fresh window: B used one permit, C uses the second, D is refused
        mockMvc.perform(get(externalUrl(project("C")))).andExpect(status().isOk());
        mockMvc.perform(get(externalUrl(project("D")))).andExpect(status().isServiceUnavailable());
    }
}
