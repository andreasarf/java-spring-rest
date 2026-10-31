package com.company.resourceapi.cache;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The cache is an optimization: with Redis unreachable the API keeps working, just uncached, and
 * requests stay fast thanks to the short Redis timeouts in application.properties.
 */
@SpringBootTest(properties = "spring.data.redis.port=1")
@AutoConfigureMockMvc
class CacheUnavailableTest {

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

    @Test
    void givenRedisDownWhenUsingCachedEndpointsThenTheyStillWork() throws Exception {
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        var project = new Project();
        project.setExternalId("NOREDIS");
        project.setSdlcSystem(sdlcSystem);
        long projectId = projectRepository.save(project).getId();
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo("/rest/api/2/project/NOREDIS"))
                .willReturn(okJson("{\"key\": \"NOREDIS\"}")));

        var start = System.nanoTime();
        mockMvc.perform(get(ProjectRestController.ENDPOINT + "/" + projectId + "/external"))
                .andExpect(status().isOk());
        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\": \"NOREDIS-2\", \"sdlcSystem\": {\"id\": " + sdlcSystem.getId() + "}}"))
                .andExpect(status().isCreated());

        // each skipped cache read/write is bounded by the Redis timeouts, not Lettuce's 60s default
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void givenRedisDownWhenSyncThenItRunsWithoutTheLock() throws Exception {
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        var project = new Project();
        project.setExternalId("NOLOCK");
        project.setSdlcSystem(sdlcSystem);
        long projectId = projectRepository.save(project).getId();
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo("/rest/api/2/project/NOLOCK"))
                .willReturn(okJson("{\"key\": \"NOLOCK\", \"name\": \"Synced without Redis\"}")));

        mockMvc.perform(post(ProjectRestController.ENDPOINT + "/" + projectId + "/sync"))
                .andExpect(status().isAccepted());

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!"Synced without Redis".equals(projectRepository.findById(projectId).orElseThrow().getName())) {
            assertThat(System.nanoTime()).as("sync not done within 10s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}
