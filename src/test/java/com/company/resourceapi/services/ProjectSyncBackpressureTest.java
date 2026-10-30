package com.company.resourceapi.services;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.controllers.ProjectRestController;
import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Background syncs are bounded: beyond the executor's limit they are rejected, not queued. */
@SpringBootTest(properties = "spring.task.execution.simple.concurrency-limit=1")
@AutoConfigureMockMvc
class ProjectSyncBackpressureTest {

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
    void givenBusyTaskExecutorWhenSyncThenReturnStatusServiceUnavailable() throws Exception {
        // given: the only background slot stays busy for 1s
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        var project = new Project();
        project.setExternalId("BUSY");
        project.setSdlcSystem(sdlcSystem);
        var syncUrl = ProjectRestController.ENDPOINT + "/" + projectRepository.save(project).getId() + "/sync";
        sdlcSystemServer.stubFor(get(urlPathMatching("/rest/api/2/project/.*"))
                .willReturn(okJson("{\"key\": \"BUSY\", \"name\": \"Busy\"}").withFixedDelay(1_000)));

        // when / then
        mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
        mockMvc.perform(post(syncUrl))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }
}
