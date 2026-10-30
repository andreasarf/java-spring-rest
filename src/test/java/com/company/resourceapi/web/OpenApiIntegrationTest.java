package com.company.resourceapi.web;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest {

    private static final String PROJECT_PATH = "$.paths['/api/v2/projects/{id}']";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void givenApiDocsWhenRequestedThenDescribeTheProjectApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title", equalTo("Resource API")))
                .andExpect(jsonPath("$.paths", hasKey("/api/v2/projects")))
                .andExpect(jsonPath("$.paths", hasKey("/api/v2/projects/{id}/external")))
                .andExpect(jsonPath(PROJECT_PATH + ".get.summary", equalTo("Get a Project")))
                .andExpect(jsonPath(PROJECT_PATH + ".patch.summary", equalTo("Update a Project")))
                // the @Parameter is documented as the real path variable, not a second parameter
                .andExpect(jsonPath(PROJECT_PATH + ".get.parameters[*].name", hasItem("id")))
                .andExpect(jsonPath(PROJECT_PATH + ".get.parameters[*].name", not(hasItem("ID"))))
                .andExpect(jsonPath(PROJECT_PATH + ".get.tags", hasItem("Project")));
    }

    @Test
    void givenApiDocsThenOnlyTheApiIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths", not(hasKey("/error"))));
    }

    @Test
    void givenSwaggerUiWhenRequestedThenServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
