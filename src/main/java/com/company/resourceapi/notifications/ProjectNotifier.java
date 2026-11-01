package com.company.resourceapi.notifications;

import com.company.resourceapi.events.ProjectCreated;
import com.company.resourceapi.events.ProjectUpdated;
import java.time.Duration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends committed project events to RabbitMQ. A send counts only once the broker confirmed it: if it
 * fails or isn't confirmed, the listener fails and the event stays in the outbox to be sent again.
 *
 * <p>Runs in the background, so a slow or unreachable broker doesn't hold up the request.
 */
@Component
public class ProjectNotifier {

    public static final String PROJECT_CREATED = "project.created";
    public static final String PROJECT_UPDATED = "project.updated";

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public ProjectNotifier(RabbitTemplate rabbitTemplate, NotificationsProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = properties.exchange();
    }

    @Async("notificationExecutor")
    @TransactionalEventListener
    public void on(ProjectCreated event) {
        send(PROJECT_CREATED, event);
    }

    @Async("notificationExecutor")
    @TransactionalEventListener
    public void on(ProjectUpdated event) {
        send(PROJECT_UPDATED, event);
    }

    private void send(String routingKey, Object event) {
        // a failure leaves the event in the outbox (and is logged by AsyncConfig's handler)
        rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(exchange, routingKey, event);
            // throws if the broker refused the message (nack, closed channel) or didn't answer in time
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT.toMillis());
            return null;
        });
    }
}
