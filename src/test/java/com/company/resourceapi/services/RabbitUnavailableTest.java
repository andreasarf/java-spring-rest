package com.company.resourceapi.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.controllers.ProjectRestController;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Without RabbitMQ the application still starts and serves requests; only syncs are refused. */
@SpringBootTest(properties = {
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.connection-timeout=200ms"})
@AutoConfigureMockMvc
class RabbitUnavailableTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectSyncLock projectSyncLock;

    @Test
    void givenRabbitUnreachableWhenSyncThenServiceUnavailableAndLockReleased() throws Exception {
        long started = System.nanoTime();

        mockMvc.perform(post(ProjectRestController.ENDPOINT + "/1/sync"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        // not left locked: the lock can be taken again
        var lease = projectSyncLock.tryAcquire(1);
        assertThat(lease).isPresent();
        lease.get().release();
    }

    @Test
    void givenRabbitUnreachableThenProjectsCanStillBeRead() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(ProjectRestController.ENDPOINT + "/1"))
                .andExpect(status().isOk());
    }
}
