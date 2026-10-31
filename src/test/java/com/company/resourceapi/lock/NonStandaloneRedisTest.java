package com.company.resourceapi.lock;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** A Sentinel (or Cluster) Redis doesn't stop the application: syncs run without the lock. */
@SpringBootTest(properties = {
        "spring.data.redis.sentinel.master=mymaster",
        "spring.data.redis.sentinel.nodes=localhost:1"})
@AutoConfigureMockMvc
class NonStandaloneRedisTest {

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
    private ObjectProvider<RedissonClient> redisson;

    @Test
    void givenSentinelRedisThenApplicationStartsAndSyncsRunUnlocked() throws Exception {
        assertThat(redisson.getIfAvailable()).isNull();

        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        var project = new Project();
        project.setExternalId("SENTINEL");
        project.setSdlcSystem(sdlcSystem);
        long projectId = projectRepository.save(project).getId();
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo("/rest/api/2/project/SENTINEL"))
                .willReturn(okJson("{\"key\": \"SENTINEL\", \"name\": \"Synced unlocked\"}")));

        mockMvc.perform(post(ProjectRestController.ENDPOINT + "/" + projectId + "/sync"))
                .andExpect(status().isAccepted());

        var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!"Synced unlocked".equals(projectRepository.findById(projectId).orElseThrow().getName())) {
            assertThat(System.nanoTime()).as("sync not done within 15s").isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}
