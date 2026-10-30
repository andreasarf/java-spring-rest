package com.company.resourceapi.controllers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.clients.SdlcProjectClient;
import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.services.ProjectService;
import com.company.resourceapi.services.SdlcSystemService;
import java.util.Optional;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

@WebMvcTest(ProjectRestController.class)
class ProjectRestControllerSdlcFailureTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private SdlcSystemService sdlcSystemService;

    @MockitoBean
    private SdlcProjectClient sdlcProjectClient;

    @BeforeEach
    void setup() throws Exception {
        var project = new Project();
        project.setExternalId("SAMPLEPROJECT");
        project.setSdlcSystem(new SdlcSystem());
        when(projectService.getProject(1)).thenReturn(project);
    }

    @Test
    void givenExhaustedConnectionPoolWhenGetExternalThenReturnStatusServiceUnavailable() throws Exception {
        when(sdlcProjectClient.fetchProject(any(), anyString())).thenThrow(new ResourceAccessException(
                "I/O error", new ConnectionRequestTimeoutException("Timeout deadline: 2000 MILLISECONDS")));

        mockMvc.perform(get(ProjectRestController.ENDPOINT + "/1/external"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    void givenNoTracerWhenHandledThenNoTraceIdHeader() throws Exception {
        // this web slice loads no tracing: the filter must not send an empty trace id
        when(sdlcProjectClient.fetchProject(any(), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get(ProjectRestController.ENDPOINT + "/1/external"))
                .andExpect(header().doesNotExist("X-Trace-Id"));
    }

    @Test
    void givenUnreachableSdlcSystemWhenGetExternalThenReturnStatusBadGateway() throws Exception {
        when(sdlcProjectClient.fetchProject(any(), anyString()))
                .thenThrow(new ResourceAccessException("I/O error", new java.net.ConnectException("refused")));

        mockMvc.perform(get(ProjectRestController.ENDPOINT + "/1/external"))
                .andExpect(status().isBadGateway());
    }
}
