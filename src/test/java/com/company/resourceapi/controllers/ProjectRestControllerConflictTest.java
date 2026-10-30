package com.company.resourceapi.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.clients.SdlcProjectClient;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.services.ProjectService;
import com.company.resourceapi.services.SdlcSystemService;
import java.sql.SQLException;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProjectRestController.class)
class ProjectRestControllerConflictTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private SdlcSystemService sdlcSystemService;

    @MockitoBean
    private SdlcProjectClient sdlcProjectClient;

    @Test
    void givenConcurrentDuplicateWhenPostThenReturnStatusConflict() throws Exception {
        // given: the conflict check passes, but a concurrent request inserted the same project first
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setId(1);
        when(sdlcSystemService.getSdlcSystem(1)).thenReturn(sdlcSystem);
        when(projectService.getProject(anyString(), anyLong())).thenReturn(Optional.empty());
        when(projectService.createProject(any())).thenThrow(new DataIntegrityViolationException(
                "Unique index or primary key violation", new ConstraintViolationException(
                        "duplicate", new SQLException(), ConstraintKind.UNIQUE, "system_project")));

        // when / then
        mockMvc.perform(post(ProjectRestController.ENDPOINT)
                .content("{\"externalId\": \"DUPLICATE\", \"sdlcSystem\": {\"id\": 1}}")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }
}
