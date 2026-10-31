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
@SpringBootTest
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

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(externalUrl()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", equalTo("From SDLC")));
        }

        sdlcSystemServer.verify(1, getRequestedFor(urlEqualTo(SDLC_PROJECT_URL)));
        // stored as JSON in Redis and read back as the record, not a map
        assertThat(cacheManager.getCache(EXTERNAL_PROJECTS).get(externalKey(), ExternalProject.class))
                .isEqualTo(new ExternalProject("CACHED", "From SDLC", null));
    }

    @Test
    void givenProjectUnknownToSdlcSystemThenItIsNotCached() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(notFound().withHeader("Content-Type", "application/json").withBody("{}")));

        mockMvc.perform(get(externalUrl())).andExpect(status().isNotFound());
        mockMvc.perform(get(externalUrl())).andExpect(status().isNotFound());

        // asked again, so the project is found as soon as it exists in the SDLC system
        sdlcSystemServer.verify(2, getRequestedFor(urlEqualTo(SDLC_PROJECT_URL)));
        assertThat(cacheManager.getCache(EXTERNAL_PROJECTS).get(externalKey())).isNull();
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

        var keys = redisTemplate.keys("*" + SDLC_SYSTEMS + "::" + sdlcSystem.getId());
        // this context's prefix (RedisTestContainerInitializer), so contexts don't share entries
        assertThat(keys).singleElement().asString().startsWith(keyPrefix + SDLC_SYSTEMS + "::");
        assertThat(redisTemplate.getExpire(keys.iterator().next())).isBetween(1L, 600L);
    }
}
