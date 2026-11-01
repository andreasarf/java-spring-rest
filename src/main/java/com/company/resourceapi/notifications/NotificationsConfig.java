package com.company.resourceapi.notifications;

import com.company.resourceapi.events.ProjectCreated;
import com.company.resourceapi.events.ProjectUpdated;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sends project events to other systems through RabbitMQ, without losing them (an outbox).
 *
 * <p>Spring Modulith's event publication registry stores each event in the event_publication table,
 * in the transaction that changed the project, so it exists exactly if the change was committed.
 * After commit, {@link ProjectNotifier} sends it to the {@code resourceapi.notifications.exchange}
 * topic exchange and it is removed once the broker confirmed it. An event that couldn't be sent
 * (RabbitMQ down or refusing it, application stopped) stays in the table and is sent again by
 * {@link UnsentNotifications}: delivery is at least once, so consumers should tolerate duplicates.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationsProperties.class)
// events are stored and sent as JSON
@RegisterReflectionForBinding({ProjectCreated.class, ProjectUpdated.class})
public class NotificationsConfig {

    /** Declared on the broker when the application first connects. */
    @Bean
    TopicExchange projectsExchange(NotificationsProperties properties) {
        return ExchangeBuilder.topicExchange(properties.exchange()).durable(true).build();
    }

    /**
     * Sends run here, not on the application task executor: its limit is for syncs, and a burst of
     * project changes shouldn't get notifications rejected. Virtual threads, in the caller's trace; at
     * most 16 sends at once, further ones wait for a free slot.
     */
    @Bean
    SimpleAsyncTaskExecutor notificationExecutor() {
        var executor = new SimpleAsyncTaskExecutor("notify-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(16);
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        return executor;
    }

    /** Messages as JSON (Boot applies it to the RabbitTemplate). */
    @Bean
    MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
