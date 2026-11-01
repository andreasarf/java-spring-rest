package com.company.resourceapi.config;

import com.company.resourceapi.services.ProjectSyncRequest;
import java.time.Duration;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable queue of sync requests: a request survives restarts and waits for a free consumer instead of
 * being refused. A sync that still fails after its retries goes to the dead-letter queue
 * ({@code <queue>.dead}), where it can be inspected and moved back.
 */
@Configuration
@EnableConfigurationProperties(ProjectSyncQueueConfig.SyncQueueProperties.class)
// requests are sent and read as JSON
@RegisterReflectionForBinding(ProjectSyncRequest.class)
public class ProjectSyncQueueConfig {

    /** Declared on the broker when the application first connects. */
    @Bean
    Queue projectSyncQueue(SyncQueueProperties properties) {
        return QueueBuilder.durable(properties.queue())
                // through the default exchange, straight to the dead-letter queue
                .deadLetterExchange("")
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    @Bean
    Queue projectSyncDeadLetterQueue(SyncQueueProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    /**
     * A message the listener deliberately dead-letters (it logged why) isn't logged again with a stack
     * trace; any other listener failure still is.
     */
    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> deadLetteringLogging() {
        return container -> container.setErrorHandler(new ConditionalRejectingErrorHandler() {
            @Override
            protected void log(Throwable failure) {
                if (causeChainContainsARADRE(failure)) {
                    logger.debug("Message dead-lettered: " + failure.getMessage());
                } else {
                    super.log(failure);
                }
            }
        });
    }

    /**
     * @param queue name of the sync request queue
     * @param maxRetries retries of a sync that failed for a reason that may pass (SDLC system
     *     unreachable or failing, our own rate limit)
     * @param retryDelay wait before the first retry, multiplied by {@code retryMultiplier} for each further one
     */
    @ConfigurationProperties("resourceapi.sync")
    public record SyncQueueProperties(
            @DefaultValue("resourceapi.project-sync") String queue,
            @DefaultValue("2") long maxRetries,
            @DefaultValue("1s") Duration retryDelay,
            @DefaultValue("2") double retryMultiplier) {

        public String deadLetterQueue() {
            return queue + ".dead";
        }
    }
}
