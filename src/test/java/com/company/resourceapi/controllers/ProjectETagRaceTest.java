package com.company.resourceapi.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Conditional updates racing for one project's row: never a lost update, never a server error. */
@SpringBootTest
@AutoConfigureMockMvc
class ProjectETagRaceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void givenUpdateHoldingTheRowThenSecondUpdateWithSameETagWaitsAndIsRefused() throws Exception {
        long id = newProject();
        var url = ProjectRestController.ENDPOINT + "/" + id;
        var etag = etagOf(url);

        // a first update has locked the row and renames the project within the lock timeout
        var first = holdRowAndRename(id, 500);

        // the second one, based on the same ETag, waits for the lock, then sees the change: 412
        var second = conditionalRename(url, etag);
        first.join();

        assertThat(second.getStatus()).isEqualTo(412);
        assertThat(projectRepository.findById(id).orElseThrow().getName()).isEqualTo("First");
    }

    @Test
    void givenRowHeldLongerThanTheLockTimeoutThenUpdateIsRefusedAsBusy() throws Exception {
        long id = newProject();
        var url = ProjectRestController.ENDPOINT + "/" + id;
        var etag = etagOf(url);

        var first = holdRowAndRename(id, 3_000);

        // not a server error: the client may retry
        var second = conditionalRename(url, etag);
        first.join();

        assertThat(second.getStatus()).isEqualTo(503);
        assertThat(second.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(projectRepository.findById(id).orElseThrow().getName()).isEqualTo("First");
    }

    private long newProject() {
        var project = new Project();
        project.setExternalId("RACE-" + UUID.randomUUID());
        project.setName("Original");
        project.setSdlcSystem(sdlcSystemRepository.findById(1L).orElseThrow());
        return projectRepository.save(project).getId();
    }

    private String etagOf(String url) throws Exception {
        return mockMvc.perform(get(url)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    /** Locks the row in another transaction, keeps it for {@code holdMillis}, renames it to "First". */
    private CompletableFuture<Void> holdRowAndRename(long id, long holdMillis) throws InterruptedException {
        var locked = new CountDownLatch(1);
        var first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var held = projectRepository.findForUpdateById(id).orElseThrow();
            locked.countDown();
            sleep(holdMillis);
            held.setName("First");
        }));
        locked.await();
        return first;
    }

    private MockHttpServletResponse conditionalRename(String url, String etag) throws Exception {
        return mockMvc.perform(patch(url).header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Second\"}"))
                .andReturn().getResponse();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
