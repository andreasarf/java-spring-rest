package com.company.resourceapi.lock;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One sync per project at a time, against a real Redis; "another instance" holds the lock directly.
 * The application's lease is long, so a lock that is free again was released, not expired.
 */
@SpringBootTest(properties = "resourceapi.sync.lock-lease-time=30s")
@AutoConfigureMockMvc
class ProjectSyncLockIntegrationTest {

    private static final String EXTERNAL_ID = "LOCKED";
    private static final String SDLC_PROJECT_URL = "/rest/api/2/project/" + EXTERNAL_ID;

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

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${resourceapi.redisson.key-prefix}")
    private String keyPrefix;

    private long projectId;
    private String syncUrl;

    @BeforeEach
    void setup() {
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        var project = new Project();
        project.setExternalId(EXTERNAL_ID);
        project.setName("Old name");
        project.setSdlcSystem(sdlcSystem);
        projectId = projectRepository.save(project).getId();
        syncUrl = ProjectRestController.ENDPOINT + "/" + projectId + "/sync";
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"LOCKED\", \"name\": \"New name\"}")));
    }

    @Test
    void givenSyncRunningElsewhereWhenSyncThenReturnStatusConflict() throws Exception {
        // another instance holds the project's lock (same Redis, different owner)
        var otherInstanceLock = redisson.getLock("project-sync:" + projectId);
        assertThat(otherInstanceLock.tryLockAsync(0, 10, TimeUnit.SECONDS, 4711L).toCompletableFuture().join()).isTrue();
        try {
            mockMvc.perform(post(syncUrl)).andExpect(status().isConflict());

            Thread.sleep(200);
            sdlcSystemServer.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            otherInstanceLock.unlockAsync(4711L).toCompletableFuture().join();
        }

        // released: syncing works again
        mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
        await(() -> projectRepository.findById(projectId).orElseThrow().getName().equals("New name"));
    }

    @Test
    void givenSlowSyncWhenSyncedTwiceThenSecondIsRefusedUntilFirstEnds() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"LOCKED\", \"name\": \"New name\"}").withFixedDelay(800)));

        mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
        mockMvc.perform(post(syncUrl)).andExpect(status().isConflict());

        // the first sync releases the lock when it ends, long before its 30s lease would expire
        await(() -> projectRepository.findById(projectId).orElseThrow().getName().equals("New name"));
        await(() -> !redisson.getLock("project-sync:" + projectId).isLocked(), Duration.ofMillis(500));
        mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
        sdlcSystemServer.verify(1, getRequestedFor(urlEqualTo(SDLC_PROJECT_URL)));
    }

    @Test
    void givenLockNeverReleasedThenItExpiresAfterItsLeaseTime() throws Exception {
        // an instance took the lock and died: nobody releases it
        var abandonedLock = redisson.getLock("project-sync:" + projectId);
        abandonedLock.tryLockAsync(0, 2, TimeUnit.SECONDS, 4712L).toCompletableFuture().join();
        mockMvc.perform(post(syncUrl)).andExpect(status().isConflict());

        await(() -> !abandonedLock.isLocked());
        mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
    }

    @Test
    void givenLockNameThenItCarriesThisContextsPrefix() {
        redisson.getLock("project-sync:" + projectId).tryLockAsync(0, 2, TimeUnit.SECONDS, 4713L)
                .toCompletableFuture().join();

        // raw Redis keys (Redisson's own key API would apply the prefix to the pattern too):
        // RedisTestContainerInitializer gives each test context its own prefix
        assertThat(redisTemplate.keys(keyPrefix + "project-sync:" + projectId)).hasSize(1);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        await(condition, Duration.ofSeconds(5));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        var deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met within " + timeout).isBefore(deadline);
            Thread.sleep(50);
        }
    }
}
