package com.company.resourceapi.clients;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.company.resourceapi.entities.SdlcSystem;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Example {@link RestClient} usage: looks a project up in the SDLC system it belongs to, using the
 * Jira-style {@code GET /rest/api/2/project/{key}} resource.
 */
@Component
public class SdlcProjectClient {

    static final String PROJECT_PATH = "/rest/api/2/project/";

    private final RestClient restClient;

    /** The builder comes with the shared connection pool, see {@code HttpClientPoolConfig}. */
    public SdlcProjectClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * @return the project, or empty if the SDLC system's API reports it as unknown
     * @throws RestClientException if the SDLC system fails, can't be reached, isn't a valid URL, or
     *         answers without a project
     */
    public Optional<ExternalProject> fetchProject(SdlcSystem sdlcSystem, String externalId) {
        ExternalProject project;
        try {
            project = restClient.get()
                    .uri(projectUri(sdlcSystem.getBaseUrl(), externalId))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(ExternalProject.class);
        } catch (HttpClientErrorException.NotFound e) {
            // Only the API's own JSON 404 means "unknown project"; an HTML 404 from the web server or
            // a proxy points at a wrong base URL and is a failure
            if (isJson(e.getResponseHeaders())) {
                return Optional.empty();
            }
            throw e;
        }
        if (project == null || project.key() == null) {
            throw new RestClientException("SDLC system returned no project for " + externalId);
        }
        return Optional.of(project);
    }

    /** The stored base URL is used as an already encoded URI; only the key is encoded here. */
    static URI projectUri(String baseUrl, String externalId) {
        try {
            return UriComponentsBuilder.fromUriString(baseUrl)
                    .path(PROJECT_PATH)
                    .path(UriUtils.encodePathSegment(externalId, UTF_8))
                    .build(true)
                    .toUri();
        } catch (IllegalArgumentException e) {
            throw new RestClientException("Invalid SDLC system base URL: " + baseUrl, e);
        }
    }

    private static boolean isJson(HttpHeaders headers) {
        var contentType = headers == null ? null : headers.getContentType();
        return contentType != null && contentType.getSubtype().endsWith("json");
    }
}
