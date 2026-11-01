package com.company.resourceapi.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.resourceapi.controllers.ProjectRestController;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Project events reach RabbitMQ through the outbox, also when the first send fails. */
@SpringBootTest(properties = {
        // resubmitted by the test, not on a schedule
        "resourceapi.notifications.resubmit-interval=1h",
        "resourceapi.notifications.resubmit-after=1ms",
        // a listener whose condition doesn't match must not leave an event behind in the outbox
        "resourceapi.sync.on-change=true"})
@AutoConfigureMockMvc
class NotificationsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private TopicExchange projectsExchange;

    @Autowired
    private UnsentNotifications unsentNotifications;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SdlcSystem sdlcSystem;
    private Queue queue;

    @BeforeEach
    void setup() {
        sdlcSystem = new SdlcSystem();
        // nothing listens there: the sync started after a create fails fast, which doesn't matter here
        sdlcSystem.setBaseUrl("http://localhost:1");
        sdlcSystem = sdlcSystemRepository.save(sdlcSystem);
        queue = bindQueue();
    }

    @AfterEach
    void cleanup() {
        if (queue != null) {
            amqpAdmin.deleteQueue(queue.getName());
        }
    }

    @Test
    void givenCreatedProjectThenNotificationIsSentAndOutboxEmptied() throws Exception {
        var id = create("NOTIFY-1");

        var message = receive();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("project.created");
        assertThat(body(message)).contains("\"projectId\":" + id).contains("\"externalId\":\"NOTIFY-1\"");
        // sent in the trace of the request that created the project
        assertThat(message.getMessageProperties().getHeaders()).containsKey("traceparent");
        await(() -> outboxSize() == 0);
    }

    @Test
    void givenUpdatedProjectThenNotificationNamesTheChange() throws Exception {
        var id = create("NOTIFY-2");
        receive();

        mockMvc.perform(patch(ProjectRestController.ENDPOINT + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Renamed\"}"))
                .andExpect(status().isOk());

        var message = receive();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("project.updated");
        assertThat(body(message)).contains("\"projectId\":" + id).contains("\"changed\":[\"NAME\"]");
        await(() -> outboxSize() == 0);
    }

    @Test
    void givenNotificationThatCouldNotBeSentThenItIsSentLater() throws Exception {
        // the broker refuses the message: here because the exchange is gone, as for any unreachable broker
        amqpAdmin.deleteExchange(projectsExchange.getName());

        create("NOTIFY-3");

        await(() -> outboxSize() == 1);
        assertThat(rabbitTemplate.receive(queue.getName(), 300)).isNull();

        // the broker is back
        amqpAdmin.declareExchange(projectsExchange);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(projectsExchange).with("project.#"));
        unsentNotifications.resubmit();

        var message = receive();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("project.created");
        assertThat(body(message)).contains("\"externalId\":\"NOTIFY-3\"");
        await(() -> outboxSize() == 0);
    }

    private long create(String externalId) throws Exception {
        var location = mockMvc.perform(post(ProjectRestController.ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalId\": \"%s\", \"name\": \"Name\", \"sdlcSystem\": {\"id\": %d}}"
                                .formatted(externalId, sdlcSystem.getId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private Queue bindQueue() {
        // durable and not auto-delete (each receive() is a short-lived consumer); deleted after the test
        var testQueue = new Queue("test-" + UUID.randomUUID(), true, false, false);
        amqpAdmin.declareQueue(testQueue);
        amqpAdmin.declareBinding(BindingBuilder.bind(testQueue).to(projectsExchange).with("project.#"));
        return testQueue;
    }

    private Message receive() {
        var message = rabbitTemplate.receive(queue.getName(), 5_000);
        assertThat(message).as("notification within 5s").isNotNull();
        return message;
    }

    private static String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    private int outboxSize() {
        return jdbcTemplate.queryForObject("select count(*) from event_publication", Integer.class);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition not met within 5s").isBefore(deadline);
            Thread.sleep(50);
        }
    }
}
