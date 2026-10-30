package com.company.resourceapi.controllers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** End to end: the endpoint calls a WireMock server standing in for the project's SDLC system. */
@SpringBootTest(properties = "spring.http.clients.read-timeout=500ms")
@AutoConfigureMockMvc
class ExternalProjectIntegrationTest {

    private static final String EXTERNAL_ENDPOINT = ProjectRestController.ENDPOINT + "/%d/external";
    private static final String EXTERNAL_ID = "WIREMOCK";
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

    private String endpoint;

    @BeforeEach
    void setup() {
        // A fresh SDLC system per test, pointing at this test's WireMock server
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);

        var project = new Project();
        project.setExternalId(EXTERNAL_ID);
        project.setSdlcSystem(sdlcSystem);
        endpoint = EXTERNAL_ENDPOINT.formatted(projectRepository.save(project).getId());
    }

    @Test
    void givenProjectKnownToSdlcSystemWhenGetThenReturnItsDetails() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"WIREMOCK\", \"name\": \"WireMock Project\"}")));

        mockMvc.perform(get(endpoint))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key", equalTo(EXTERNAL_ID)))
                .andExpect(jsonPath("$.name", equalTo("WireMock Project")));
    }

    @Test
    void givenProjectUnknownToSdlcSystemWhenGetThenReturnStatusNotFound() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(notFound().withHeader("Content-Type", "application/json")
                        .withBody("{\"errorMessages\": [\"No project could be found\"]}")));

        mockMvc.perform(get(endpoint))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenWrongBaseUrlWhenGetThenReturnStatusBadGateway() throws Exception {
        // an HTML 404 from the web server is not the API saying "unknown project"
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(notFound().withHeader("Content-Type", "text/html")));

        mockMvc.perform(get(endpoint))
                .andExpect(status().isBadGateway());
    }

    @Test
    void givenSdlcSystemErrorWhenGetThenReturnStatusBadGateway() throws Exception {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(get(endpoint))
                .andExpect(status().isBadGateway());
    }

    @Test
    void givenSlowSdlcSystemWhenGetThenReturnStatusBadGateway() throws Exception {
        // responds after the 500ms read timeout configured for this test
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"WIREMOCK\"}").withFixedDelay(2_000)));

        mockMvc.perform(get(endpoint))
                .andExpect(status().isBadGateway());
    }

    @Test
    void givenUnknownProjectWhenGetThenReturnStatusNotFoundWithoutCallingSdlcSystem() throws Exception {
        mockMvc.perform(get(EXTERNAL_ENDPOINT.formatted(12345)))
                .andExpect(status().isNotFound());

        sdlcSystemServer.verify(0, anyRequestedFor(anyUrl()));
    }
}
