package com.company.resourceapi.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.controllers.ProjectRestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Requests per client address, shared by all instances through Redis. */
@SpringBootTest(properties = {
        "resourceapi.rate-limit.client.permits=3",
        "resourceapi.rate-limit.client.period=2s"})
@AutoConfigureMockMvc
class ClientRateLimitIntegrationTest {

    private static final String PROJECT_URL = ProjectRestController.ENDPOINT + "/1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void givenTooManyRequestsFromOneClientThenFurtherOnesAreRefused() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(PROJECT_URL).with(from("10.0.0.1"))).andExpect(status().isOk());
        }
        mockMvc.perform(get(PROJECT_URL).with(from("10.0.0.1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(header().exists("X-Trace-Id"));

        // another client has its own limit
        mockMvc.perform(get(PROJECT_URL).with(from("10.0.0.2"))).andExpect(status().isOk());
    }

    @Test
    void givenNonApiPathsThenTheyAreNotLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/v3/api-docs").with(from("10.0.0.3"))).andExpect(status().isOk());
        }
        mockMvc.perform(get(PROJECT_URL).with(from("10.0.0.3"))).andExpect(status().isOk());
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
