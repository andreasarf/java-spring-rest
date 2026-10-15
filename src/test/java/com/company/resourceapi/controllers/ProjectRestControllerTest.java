package com.company.resourceapi.controllers;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class ProjectRestControllerTest {

    private final String SDLC_SYSTEM_JSON_TEMPLATE = "\"sdlcSystem\": {\"id\": %d}";
    private final String SDLC_SYSTEM_JSON_TEMPLATE2 = "{\"sdlcSystem\": {\"id\": %d}}";
    private final String PROJECT_JSON_TEMPLATE
            = "{\"externalId\": \"%s\",\"name\": \"%s\"," + SDLC_SYSTEM_JSON_TEMPLATE + "}";
    private final String ENDPOINT_WITH_ID = ProjectRestController.ENDPOINT + "/%d";
    private final String EXTERNALID_JPATH = "$.externalId";
    private final String NAME_JPATH = "$.name";
    private final String SYSTEM_ID_JPATH = "$.sdlcSystem.id";

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    @Rollback
    void tearDown() {

    }

    @Test
    void givenProjectIdWhenGetThenReturnStatusOk() throws Exception {
        final long PROJECT_ID = 1;
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        mockMvc.perform(get(requestUri))
                .andExpect(status().isOk());
    }

    @Test
    void givenProjectIdWhenGetThenReturnStatusNotFound() throws Exception {
        final long PROJECT_ID = 12345;
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        mockMvc.perform(get(requestUri))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenProjectJsonWhenPostThenReturnStatusCreated() throws Exception {
        final String jsonContent = String.format(PROJECT_JSON_TEMPLATE, "EXTERNALID", "Name", 1);
        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content(jsonContent)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated());
    }

    @Test
    void givenProjectJsonWhenPostThenReturnStatusConflict() throws Exception {
        final String jsonContent = String.format(PROJECT_JSON_TEMPLATE, "SAMPLEPROJECT", "Name", 1);
        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content(jsonContent)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }

    @Test
    void givenProjectJsonWhenPostThenReturnStatusNotFound() throws Exception {
        final String jsonContent = String.format(PROJECT_JSON_TEMPLATE, "EXTERNALID", "Name", 123);
        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content(jsonContent)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenProjectJsonWhenPostThenReturnStatusBadRequest() throws Exception {
        final String jsonContent = String.format(SDLC_SYSTEM_JSON_TEMPLATE2, 123);
        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content(jsonContent)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenIdAndPatchedProjectWhenPatchedThenReturnStatusOk() throws Exception {
        final long PROJECT_ID = 5;
        final String PATCHED_NAME = "Name-Edited";
        final String PATCHED_EXTERNALID = "EXTERNALIDEDITED";
        final long PATCHED_SYSTEM_ID = 1;
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        final String jsonContent = String.format(PROJECT_JSON_TEMPLATE,
                PATCHED_EXTERNALID, PATCHED_NAME, PATCHED_SYSTEM_ID);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath(EXTERNALID_JPATH, equalTo(PATCHED_EXTERNALID)))
                .andExpect(jsonPath(NAME_JPATH, equalTo(PATCHED_NAME)))
                .andExpect(jsonPath(SYSTEM_ID_JPATH, equalTo((int) PATCHED_SYSTEM_ID)));
    }

    @Test
    void givenIdAndPatchedProjectWhenPatchedThenReturnStatusOk2() throws Exception {
        // given
        final long PROJECT_ID = 6;
        final String PATCHED_EXTERNALID = "EXTERNALIDEDITED";
        final String PROJECT_NAME = "Project One";
        final int PROJECT_SYSTEM_ID = 3;
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        final String jsonContent = String.format("{\"externalId\": \"%s\"}",
                PATCHED_EXTERNALID);

        // when
        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonContent))
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath(EXTERNALID_JPATH, equalTo(PATCHED_EXTERNALID)))
                .andExpect(jsonPath(NAME_JPATH, equalTo(PROJECT_NAME)))
                .andExpect(jsonPath(SYSTEM_ID_JPATH, equalTo(PROJECT_SYSTEM_ID)));
    }

    @Test
    void givenIdAndPatchedProjectWhenPatchedThenReturnStatusBadRequest() throws Exception {
        final long PROJECT_ID = 1;
        final String PATCHED_SYSTEM_ID = "whatever";
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        final String jsonContent = String.format("{\"sdlcSystem\": {\"id\": \"%s\"}}",
                PATCHED_SYSTEM_ID);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonContent))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenIdAndPatchedProjectWhenPatchedThenReturnStatusNotFound() throws Exception {
        final long PROJECT_ID = 1;
        final long PATCHED_SYSTEM_ID = 12345;
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        final String jsonContent = String.format(SDLC_SYSTEM_JSON_TEMPLATE2,
                PATCHED_SYSTEM_ID);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonContent))
                .andExpect(status().isNotFound());
    }

    @Test
    void givenIdAndPatchedProjectWhenPatchedThenReturnStatusConflict() throws Exception {
        // given
        final long PROJECT_ID = 1;
        final String PATCHED_EXTERNALID = "PROJECTX";
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        final String jsonContent = String.format("{\"externalId\": \"%s\"}", PATCHED_EXTERNALID);

        // when
        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonContent))
                // then
                .andExpect(status().isConflict());
    }

    @Test
    void givenProjectJsonWithIdWhenPostThenCreateNewProjectInsteadOfOverwriting() throws Exception {
        final long EXISTING_PROJECT_ID = 1;
        final String jsonContent = "{\"id\": " + EXISTING_PROJECT_ID + ","
                + String.format(PROJECT_JSON_TEMPLATE, "NEWPROJECT", "New Project", 3).substring(1);

        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content(jsonContent)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(equalTo((int) EXISTING_PROJECT_ID))))
                .andExpect(jsonPath(EXTERNALID_JPATH, equalTo("NEWPROJECT")));

        mockMvc.perform(get(String.format(ENDPOINT_WITH_ID, EXISTING_PROJECT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(EXTERNALID_JPATH, equalTo("SAMPLEPROJECT")));
    }

    @Test
    void givenProjectJsonWhenPostThenReturnFullSdlcSystem() throws Exception {
        final String jsonContent = String.format(PROJECT_JSON_TEMPLATE, "FULLSYSTEM", "Name", 2);

        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content(jsonContent)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sdlcSystem.baseUrl", equalTo("http://bugzilla.company.com")));
    }

    @Test
    void givenUnchangedExternalIdWhenPatchedThenReturnStatusOk() throws Exception {
        final long PROJECT_ID = 8;
        final String UNCHANGED_EXTERNALID = "PROJECTTHREE";
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);
        final String jsonContent = String.format("{\"externalId\": \"%s\"}", UNCHANGED_EXTERNALID);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath(EXTERNALID_JPATH, equalTo(UNCHANGED_EXTERNALID)));
    }

    @Test
    void givenNullSdlcSystemWhenPatchedThenReturnStatusBadRequest() throws Exception {
        final long PROJECT_ID = 1;
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sdlcSystem\": null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenNewSdlcSystemWhenPatchedThenReturnFullSdlcSystem() throws Exception {
        final long PROJECT_ID = 7;
        String requestUri = String.format(ENDPOINT_WITH_ID, PROJECT_ID);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format(SDLC_SYSTEM_JSON_TEMPLATE2, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SYSTEM_ID_JPATH, equalTo(2)))
                .andExpect(jsonPath("$.sdlcSystem.baseUrl", equalTo("http://bugzilla.company.com")));
    }

    @Test
    void givenTooLongExternalIdWhenPostThenReturnStatusBadRequest() throws Exception {
        final String jsonContent = String.format(PROJECT_JSON_TEMPLATE, "X".repeat(256), "Name", 1);

        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content(jsonContent)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenTooLongNameWhenPatchedThenReturnStatusBadRequest() throws Exception {
        String requestUri = String.format(ENDPOINT_WITH_ID, 1);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"name\": \"%s\"}", "N".repeat(256))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenBlankExternalIdWhenPatchedThenReturnStatusBadRequest() throws Exception {
        String requestUri = String.format(ENDPOINT_WITH_ID, 1);

        mockMvc.perform(patch(requestUri)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalId\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void givenRepositoryPathWhenGetThenReturnStatusNotFound() throws Exception {
        // Repositories must only be reachable through the validated controller endpoints
        mockMvc.perform(get("/projects"))
                .andExpect(status().isNotFound());
    }
}
