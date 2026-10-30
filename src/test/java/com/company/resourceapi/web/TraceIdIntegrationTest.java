package com.company.resourceapi.web;

import static com.company.resourceapi.web.TraceIdResponseHeaderFilter.TRACE_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TraceIdIntegrationTest {

    private static final String PROJECT_URL = "/api/v2/projects/1";
    private static final String INCOMING_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void givenRequestsWhenHandledThenEachResponseCarriesItsOwnTraceId() throws Exception {
        var first = mockMvc.perform(get(PROJECT_URL)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(TRACE_ID_HEADER);
        var second = mockMvc.perform(get(PROJECT_URL)).andExpect(status().isOk())
                .andReturn().getResponse().getHeader(TRACE_ID_HEADER);

        assertThat(first).matches("[0-9a-f]{32}");
        assertThat(second).matches("[0-9a-f]{32}").isNotEqualTo(first);
    }

    @Test
    void givenIncomingTraceparentWhenHandledThenTraceIsContinued() throws Exception {
        mockMvc.perform(get(PROJECT_URL)
                        .header("traceparent", "00-" + INCOMING_TRACE_ID + "-00f067aa0ba902b7-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(TRACE_ID_HEADER, INCOMING_TRACE_ID));
    }

    @Test
    void givenErrorResponseThenTraceIdIsStillReturned() throws Exception {
        var traceId = mockMvc.perform(get("/api/v2/projects/12345"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getHeader(TRACE_ID_HEADER);

        assertThat(traceId).matches("[0-9a-f]{32}");
    }
}
