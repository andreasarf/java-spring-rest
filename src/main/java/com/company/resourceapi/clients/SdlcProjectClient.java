package com.company.resourceapi.clients;

import com.company.resourceapi.entities.SdlcSystem;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Example {@link RestClient} usage: looks a project up in the SDLC system it belongs to, using the
 * Jira-style {@code GET /rest/api/2/project/{key}} resource.
 */
@Component
public class SdlcProjectClient {

    static final String PROJECT_PATH = "/rest/api/2/project/{key}";

    private final RestClient restClient;

    /**
     * Uses the auto-configured builder (message converters, customizers) with Boot's shared
     * request factory, so all calls go through one connection pool that is closed on shutdown.
     * A builder on its own would create a new pool for every client built from it.
     */
    @Autowired
    public SdlcProjectClient(RestClient.Builder restClientBuilder, ClientHttpRequestFactory requestFactory) {
        this(restClientBuilder.requestFactory(requestFactory).build());
    }

    SdlcProjectClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @return the project, or empty if the SDLC system doesn't know it
     * @throws RestClientException if the SDLC system fails, can't be reached, or answers
     *         without a project
     */
    public Optional<ExternalProject> fetchProject(SdlcSystem sdlcSystem, String externalId) {
        ExternalProject project;
        try {
            project = restClient.get()
                    // the base URL is used as-is; only the path is a template, so the key is encoded
                    .uri(sdlcSystem.getBaseUrl(), uri -> uri.path(PROJECT_PATH).build(externalId))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(ExternalProject.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
        if (project == null) {
            throw new RestClientException("SDLC system returned no project for " + externalId);
        }
        return Optional.of(project);
    }
}
