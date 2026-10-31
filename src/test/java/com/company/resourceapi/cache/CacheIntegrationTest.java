package com.company.resourceapi.cache;

import static com.company.resourceapi.config.CacheConfig.EXTERNAL_PROJECTS;
import static com.company.resourceapi.config.CacheConfig.SDLC_SYSTEMS;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.clients.ExternalProject;
import com.company.resourceapi.controllers.ProjectRestController;
import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.company.resourceapi.services.SdlcSystemService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Redis cache in front of the SDLC systems, against a real Redis (RedisTestContainerInitializer). */
@SpringBootTest(properties = "resourceapi.cache.missing-external-projects-ttl=3s")
@AutoConfigureMockMvc
class CacheIntegrationTest {

    private static final String EXTERNAL_ID = "CACHED";
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
    private SdlcSystemService sdlcSystemService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${spring.cache.redis.key-prefix}")
    private String keyPrefix;

    private SdlcSystem sdlcSystem;
    private long projectId;

    @BeforeEach
    void setup() {
        sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);

        var project = new Project();
        project.setExternalId(EXTERNAL_ID);
        project.setName("Local name");
        project.setSdlcSystem(sdlcSystem);
        projectId = projectRepository.save(project).getId();
    }

    @Test
    void givenRepeatedLookupsThenSdlcSystemIsAskedOnce() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"CACHED\", \"name\": \"From SDLC\"}")));

        mockMvc.perform(get(externalUrl())).andExpect(status().isOk());
        // counted after the first lookup: the HTTP client may retry a GET on a stale pooled connection
        int callsForFirstLookup = sdlcSystemServer.findAll(getRequestedFor(urlEqualTo(SDLC_PROJECT_URL))).size();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get(externalUrl()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", equalTo("From SDLC")));
        }

        // the next lookups are served from the cache
        sdlcSystemServer.verify(callsForFirstLookup, getRequestedFor(urlEqualTo(SDLC_PROJECT_URL)));
        // stored as JSON in Redis and read back as the record, not a map
        assertThat(cacheManager.getCache(EXTERNAL_PROJECTS).get(externalKey(), ExternalProject.class))
                .isEqualTo(new ExternalProject("CACHED", "From SDLC", null));
    }

    @Test
    void givenProjectUnknownToSdlcSystemThenThatIsRememberedBriefly() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(notFound().withHeader("Content-Type", "application/json").withBody("{}")));

        mockMvc.perform(get(externalUrl())).andExpect(status().isNotFound());
        int callsForFirstLookup = sdlcSystemServer.findAll(getRequestedFor(urlEqualTo(SDLC_PROJECT_URL))).size();
        mockMvc.perform(get(externalUrl())).andExpect(status().isNotFound());

        // the "unknown" answer is remembered (as a cached null), with the short time to live (3s here)
        sdlcSystemServer.verify(callsForFirstLookup, getRequestedFor(urlEqualTo(SDLC_PROJECT_URL)));
        var cached = cacheManager.getCache(EXTERNAL_PROJECTS).get(externalKey());
        assertThat(cached).isNotNull();
        assertThat(cached.get()).isNull();
        assertThat(redisTemplate.getExpire(redisKey())).isBetween(1L, 3L);

        // the project is created in the SDLC system: found once the "unknown" answer expired
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"CACHED\", \"name\": \"Created later\"}")));
        await(() -> statusOf(externalUrl()) == 200);
        // ... and kept with the long time to live (5m)
        assertThat(redisTemplate.getExpire(redisKey())).isGreaterThan(60L);
    }

    @Test
    void givenProjectRememberedAsUnknownWhenSyncedThenItIsFoundRightAway() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(notFound().withHeader("Content-Type", "application/json").withBody("{}")));
        mockMvc.perform(get(externalUrl())).andExpect(status().isNotFound());

        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"CACHED\", \"name\": \"Created later\"}")));
        mockMvc.perform(post(ProjectRestController.ENDPOINT + "/" + projectId + "/sync"))
                .andExpect(status().isAccepted());
        await(() -> projectRepository.findById(projectId).orElseThrow().getName().equals("Created later"));
        int callsUntilSynced = sdlcSystemServer.findAll(getRequestedFor(urlEqualTo(SDLC_PROJECT_URL))).size();

        // the sync replaced the "unknown" answer with the project, well within its 3s
        assertThat(cacheManager.getCache(EXTERNAL_PROJECTS).get(externalKey(), ExternalProject.class))
                .isEqualTo(new ExternalProject("CACHED", "Created later", null));
        mockMvc.perform(get(externalUrl())).andExpect(status().isOk());
        sdlcSystemServer.verify(callsUntilSynced, getRequestedFor(urlEqualTo(SDLC_PROJECT_URL)));
    }

    @Test
    void givenSyncThenItBypassesAndRefreshesTheCache() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"CACHED\", \"name\": \"Old name\"}")));
        mockMvc.perform(get(externalUrl())).andExpect(jsonPath("$.name", equalTo("Old name")));

        // the SDLC system renames the project; a sync must not use the cached answer
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"CACHED\", \"name\": \"New name\"}")));
        mockMvc.perform(post(ProjectRestController.ENDPOINT + "/" + projectId + "/sync"))
                .andExpect(status().isAccepted());
        await(() -> projectRepository.findById(projectId).orElseThrow().getName().equals("New name"));

        // ... and the cache now has the fresh answer, without another call
        mockMvc.perform(get(externalUrl())).andExpect(jsonPath("$.name", equalTo("New name")));
        sdlcSystemServer.verify(2, getRequestedFor(urlEqualTo(SDLC_PROJECT_URL)));
    }

    @Test
    void givenSdlcSystemLookupThenItIsServedFromTheCache() throws Exception {
        var first = sdlcSystemService.getSdlcSystem(sdlcSystem.getId());

        // changed behind the API's back: the cached value is used until it expires
        sdlcSystem.setDescription("Changed in the database");
        sdlcSystemRepository.save(sdlcSystem);
        var second = sdlcSystemService.getSdlcSystem(sdlcSystem.getId());

        assertThat(second).isNotSameAs(first);
        assertThat(second.getBaseUrl()).isEqualTo(sdlcSystemServer.baseUrl());
        assertThat(second.getDescription()).isNull();
        assertThat(cacheManager.getCache(SDLC_SYSTEMS).get(sdlcSystem.getId(), SdlcSystem.class)).isNotNull();
    }

    private String externalUrl() {
        return ProjectRestController.ENDPOINT + "/" + projectId + "/external";
    }

    private String externalKey() {
        return sdlcSystem.getId() + ":" + EXTERNAL_ID;
    }

    private String redisKey() {
        return keyPrefix + EXTERNAL_PROJECTS + "::" + externalKey();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met within 5s").isBefore(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void givenCachedValuesThenRedisKeysCarryTheConfiguredPrefixAndTtl() throws Exception {
        sdlcSystemService.getSdlcSystem(sdlcSystem.getId());

        var keys = redisTemplate.keys(keyPrefix + SDLC_SYSTEMS + "::" + sdlcSystem.getId());
        // this context's prefix (RedisTestContainerInitializer), so contexts don't share entries
        assertThat(keys).singleElement().asString().startsWith(keyPrefix + SDLC_SYSTEMS + "::");
        assertThat(redisTemplate.getExpire(keys.iterator().next())).isBetween(1L, 600L);
    }

    @Test
    void givenProjectGoneFromSdlcSystemWhenSyncedThenCachedEntryIsRemoved() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"CACHED\", \"name\": \"Soon gone\"}")));
        mockMvc.perform(get(externalUrl())).andExpect(status().isOk());

        // deleted in the SDLC system; the sync finds out
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(notFound().withHeader("Content-Type", "application/json").withBody("{}")));
        mockMvc.perform(post(ProjectRestController.ENDPOINT + "/" + projectId + "/sync"))
                .andExpect(status().isAccepted());
        await(() -> cacheManager.getCache(EXTERNAL_PROJECTS).get(externalKey()) == null);

        mockMvc.perform(get(externalUrl())).andExpect(status().isNotFound());
    }

    private int statusOf(String url) {
        try {
            return mockMvc.perform(get(url)).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
