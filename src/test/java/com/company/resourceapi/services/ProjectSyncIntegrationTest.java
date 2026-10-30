package com.company.resourceapi.services;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.controllers.ProjectRestController;
import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.company.resourceapi.web.TraceIdResponseHeaderFilter;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

/** Background sync on a virtual thread, staying in the trace of the request that started it. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ProjectSyncIntegrationTest {

    private static final String EXTERNAL_ID = "ASYNC";
    private static final String SDLC_PROJECT_URL = "/rest/api/2/project/" + EXTERNAL_ID;

    @RegisterExtension
    static WireMockExtension sdlcSystemServer = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    @Qualifier("applicationTaskExecutor")
    private AsyncTaskExecutor applicationTaskExecutor;

    @Autowired
    private Tracer tracer;

    @Autowired
    private ObservationRegistry observationRegistry;

    private long projectId;

    @BeforeEach
    void setup() {
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl(sdlcSystemServer.baseUrl());
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);

        var project = new Project();
        project.setExternalId(EXTERNAL_ID);
        project.setName("Old name");
        project.setSdlcSystem(sdlcSystem);
        projectId = projectRepository.save(project).getId();
    }

    @Test
    void givenSyncRequestThenNameIsRefreshedInBackgroundWithinTheSameTrace(CapturedOutput output) throws Exception {
        // given
        sdlcSystemServer.stubFor(WireMock.get(urlEqualTo(SDLC_PROJECT_URL))
                .willReturn(okJson("{\"key\": \"ASYNC\", \"name\": \"New name\"}")));

        // when: accepted right away, done in the background
        var traceId = mockMvc.perform(post(syncUrl(projectId)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader(TraceIdResponseHeaderFilter.TRACE_ID_HEADER);
        assertThat(traceId).matches("[0-9a-f]{32}");

        // then: the project is updated ...
        await(() -> projectRepository.findById(projectId).orElseThrow().getName().equals("New name"));
        // ... the background call to the SDLC system carries the request's trace ...
        sdlcSystemServer.verify(getRequestedFor(urlEqualTo(SDLC_PROJECT_URL))
                .withHeader("traceparent", matching("00-" + traceId + "-[0-9a-f]{16}-0[01]")));
        // ... and so does the background log line
        await(() -> output.getOut().lines().anyMatch(line ->
                line.contains("Synced project " + projectId) && line.contains(traceId)));
    }

    @Test
    void givenUnknownProjectWhenSyncThenReturnStatusNotFoundAndStartNothing() throws Exception {
        mockMvc.perform(post(syncUrl(12345)))
                .andExpect(status().isNotFound());

        sdlcSystemServer.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void givenApplicationTaskExecutorThenTasksRunOnVirtualThreads() throws Exception {
        Callable<Boolean> isVirtual = () -> Thread.currentThread().isVirtual();

        assertThat(applicationTaskExecutor.submit(isVirtual).get()).isTrue();
    }

    @Test
    void givenObservedCallerThenTasksRunInTheSameTrace() throws Exception {
        // requests are observed the same way (Boot's server observation filter)
        Observation.createNotStarted("caller", observationRegistry).observeChecked(() -> {
            var callerTraceId = tracer.currentSpan().context().traceId();
            Callable<String> traceIdInTask = () -> tracer.currentSpan() == null
                    ? null : tracer.currentSpan().context().traceId();

            assertThat(applicationTaskExecutor.submit(traceIdInTask).get()).isEqualTo(callerTraceId);
        });
    }

    private static String syncUrl(long id) {
        return ProjectRestController.ENDPOINT + "/" + id + "/sync";
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met within 5s").isBefore(deadline);
            Thread.sleep(50);
        }
    }
}
