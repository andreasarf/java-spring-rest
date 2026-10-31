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
        var syncUrl = syncUrl(sdlcSystem, "BUSY");
        var otherSyncUrl = syncUrl(sdlcSystem, "OTHER");
        sdlcSystemServer.stubFor(get(urlPathMatching("/rest/api/2/project/.*"))
                .willReturn(okJson("{\"key\": \"BUSY\", \"name\": \"Busy\"}").withFixedDelay(1_000)));

        // when / then: another project's sync (the same project's would be refused by its lock)
        mockMvc.perform(post(syncUrl)).andExpect(status().isAccepted());
        mockMvc.perform(post(otherSyncUrl))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));

        // the rejected sync released its lock: it can be started once a slot is free
        Thread.sleep(1_500);
        mockMvc.perform(post(otherSyncUrl)).andExpect(status().isAccepted());
    }

    private String syncUrl(SdlcSystem sdlcSystem, String externalId) {
        var project = new Project();
        project.setExternalId(externalId);
        project.setSdlcSystem(sdlcSystem);
        return ProjectRestController.ENDPOINT + "/" + projectRepository.save(project).getId() + "/sync";
    }
}
