package com.company.resourceapi.events;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.controllers.ProjectRestController;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** A project's name is fetched from its SDLC system after it is created or re-keyed. */
@SpringBootTest(properties = "resourceapi.sync.on-change=true")
@AutoConfigureMockMvc
class SyncOnProjectChangeTest {

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

    private SdlcSystem sdlcSystem;

    @BeforeEach
    void setup() {
        sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        stubProject("FIRST", "First from SDLC");
        stubProject("SECOND", "Second from SDLC");
    }

    @Test
    void givenProjectCreatedWithoutNameThenNameComesFromSdlcSystem() throws Exception {
        var id = create("FIRST");

        await(() -> "First from SDLC".equals(nameOf(id)));
    }

    @Test
    void givenProjectPointedAtAnotherSdlcProjectThenItsNameIsFetched() throws Exception {
        var id = create("FIRST");
        await(() -> "First from SDLC".equals(nameOf(id)));

        patchProject(id, "{\"externalId\": \"SECOND\"}");

        await(() -> "Second from SDLC".equals(nameOf(id)));
    }

    @Test
    void givenOnlyNameChangedThenSdlcSystemIsNotAsked() throws Exception {
        var id = create("FIRST");
        await(() -> "First from SDLC".equals(nameOf(id)));
        // the sync's own rename doesn't start another sync either
        int calls = sdlcCalls();

        patchProject(id, "{\"name\": \"Renamed here\"}");

        Thread.sleep(300);
        assertThat(sdlcCalls()).isEqualTo(calls);
        assertThat(nameOf(id)).isEqualTo("Renamed here");
    }

    private long create(String externalId) throws Exception {
        var location = mockMvc.perform(post(ProjectRestController.ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\": \"%s\", \"sdlcSystem\": {\"id\": %d}}"
                                .formatted(externalId, sdlcSystem.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void patchProject(long id, String body) throws Exception {
        mockMvc.perform(patch(ProjectRestController.ENDPOINT + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String nameOf(long id) {
        return projectRepository.findById(id).orElseThrow().getName();
    }

    private static int sdlcCalls() {
        return sdlcSystemServer.findAll(getRequestedFor(urlPathMatching("/rest/api/2/project/.*"))).size();
    }

    private static void stubProject(String key, String name) {
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo("/rest/api/2/project/" + key))
                .willReturn(okJson("{\"key\": \"%s\", \"name\": \"%s\"}".formatted(key, name))));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met within 5s").isBefore(deadline);
            Thread.sleep(50);
        }
    }
}
