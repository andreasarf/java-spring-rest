package com.company.resourceapi.clients;

import com.company.resourceapi.entities.SdlcSystem;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Example {@link RestClient} usage: looks a project up in the SDLC system it belongs to, using the
 * Jira-style {@code GET /rest/api/2/project/{key}} resource.
 */
@Component
public class SdlcProjectClient {

    static final String PROJECT_PATH = "/rest/api/2/project/{key}";

    private final RestClient restClient;

    /** Uses the auto-configured builder, so Boot's message converters and timeouts apply. */
    public SdlcProjectClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * @return the project, or empty if the SDLC system doesn't know it
     * @throws org.springframework.web.client.RestClientException if the SDLC system fails or
     *         can't be reached
     */
    public Optional<ExternalProject> fetchProject(SdlcSystem sdlcSystem, String externalId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(sdlcSystem.getBaseUrl() + PROJECT_PATH, externalId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(ExternalProject.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
