package com.company.resourceapi.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Conditional requests on a project: ETag, If-None-Match (304) and If-Match (412). */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectETagIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    private String url;

    @BeforeEach
    void setup() {
        var project = new Project();
        project.setExternalId("ETAG-" + UUID.randomUUID());
        project.setName("Original");
        project.setSdlcSystem(sdlcSystemRepository.findById(1L).orElseThrow());
        url = ProjectRestController.ENDPOINT + "/" + projectRepository.save(project).getId();
    }

    @Test
    void givenUnchangedProjectThenReadWithItsETagIsNotModified() throws Exception {
        var etag = etagOf(url);

        mockMvc.perform(get(url).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));
    }

    @Test
    void givenChangedProjectThenOldETagNoLongerMatches() throws Exception {
        var before = etagOf(url);

        var after = patchName(null, "Renamed")
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(after).isNotEqualTo(before);
        // the ETag returned by the update is the one a later read gives
        assertThat(etagOf(url)).isEqualTo(after);
        mockMvc.perform(get(url).header(HttpHeaders.IF_NONE_MATCH, before))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void givenCurrentETagThenConditionalUpdateApplies() throws Exception {
        var etag = etagOf(url);

        patchName(etag, "Renamed").andExpect(status().isOk());

        assertThat(nameOf(url)).isEqualTo("Renamed");
    }

    @Test
    void givenStaleETagThenConditionalUpdateIsRefused() throws Exception {
        var stale = etagOf(url);
        // someone else changes the project after it was read
        patchName(null, "Changed by someone else").andExpect(status().isOk());

        patchName(stale, "Lost update").andExpect(status().isPreconditionFailed());

        assertThat(nameOf(url)).isEqualTo("Changed by someone else");
    }

    @Test
    void givenWildcardOrWeakETagThenOnlyTheWildcardMatches() throws Exception {
        var etag = etagOf(url);

        // If-Match uses strong comparison: a weak tag never matches
        patchName("W/" + etag, "Weak").andExpect(status().isPreconditionFailed());
        patchName("*", "Any").andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions patchName(String ifMatch, String name)
            throws Exception {
        var request = patch(url).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + name + "\"}");
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mockMvc.perform(request);
    }

    private String etagOf(String url) throws Exception {
        var etag = mockMvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).matches("\"[0-9a-f]{32}\"");
        return etag;
    }

    private String nameOf(String url) throws Exception {
        return projectRepository.findById(Long.parseLong(url.substring(url.lastIndexOf('/') + 1)))
                .orElseThrow().getName();
    }
}
