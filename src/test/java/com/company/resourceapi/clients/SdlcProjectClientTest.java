package com.company.resourceapi.clients;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.resourceapi.entities.SdlcSystem;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

class SdlcProjectClientTest {

    @RegisterExtension
    static WireMockExtension sdlcSystemServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final SdlcProjectClient client = new SdlcProjectClient(RestClient.builder());

    private SdlcSystem sdlcSystem;

    @BeforeEach
    void setup() {
        sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
    }

    @Test
    void givenKnownProjectWhenFetchProjectThenReturnProject() {
        // given: a Jira-style response with more fields than the client maps
        sdlcSystemServer.stubFor(get(urlEqualTo("/rest/api/2/project/SAMPLEPROJECT"))
                .withHeader("Accept", equalTo("application/json"))
                .willReturn(okJson("""
                        {
                          "key": "SAMPLEPROJECT",
                          "name": "Sample Project",
                          "description": "The sample project",
                          "lead": {"name": "jdoe"}
                        }
                        """)));

        // when
        var project = client.fetchProject(sdlcSystem, "SAMPLEPROJECT");

        // then
        assertThat(project).contains(
                new ExternalProject("SAMPLEPROJECT", "Sample Project", "The sample project"));
    }

    @Test
    void givenUnknownProjectWhenFetchProjectThenReturnEmpty() {
        sdlcSystemServer.stubFor(get(urlEqualTo("/rest/api/2/project/UNKNOWN")).willReturn(notFound()));

        assertThat(client.fetchProject(sdlcSystem, "UNKNOWN")).isEmpty();
    }

    @Test
    void givenSdlcSystemErrorWhenFetchProjectThenThrow() {
        sdlcSystemServer.stubFor(get(urlEqualTo("/rest/api/2/project/SAMPLEPROJECT"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.fetchProject(sdlcSystem, "SAMPLEPROJECT"))
                .isInstanceOf(HttpServerErrorException.ServiceUnavailable.class);
    }

    @Test
    void givenExternalIdWithReservedCharactersWhenFetchProjectThenEncodeIt() {
        // given: a key must stay one path segment, even if it contains a space or a slash
        sdlcSystemServer.stubFor(get(urlEqualTo("/rest/api/2/project/A%20B%2F1"))
                .willReturn(okJson("{\"key\": \"A B/1\", \"name\": \"Odd key\"}")));

        // when
        var project = client.fetchProject(sdlcSystem, "A B/1");

        // then
        assertThat(project).map(ExternalProject::key).contains("A B/1");
        sdlcSystemServer.verify(1, getRequestedFor(urlEqualTo("/rest/api/2/project/A%20B%2F1")));
    }
}
