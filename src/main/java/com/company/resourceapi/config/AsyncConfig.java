package com.company.resourceapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} methods run on Boot's application task executor, which uses virtual threads
 * ({@code spring.threads.virtual.enabled}). The decorator copies the caller's context (the current
 * trace/span, MDC) to the task, so background work stays in the request's trace.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** Boot applies a TaskDecorator bean to the application task executor. */
    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}
