package com.company.resourceapi.config;

import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async} methods run on Boot's application task executor, which uses virtual threads
 * ({@code spring.threads.virtual.enabled}). The decorator copies the caller's context (the current
 * trace/span, MDC) to the task, so background work stays in the request's trace.
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /** Boot applies a TaskDecorator bean to the application task executor. */
    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    /**
     * {@code @Async} without an executor name runs on Boot's application task executor, also now
     * that other executors exist (spring.task.execution.mode=force keeps Boot creating it).
     *
     * <p>A failed fire-and-forget task (e.g. a notification that RabbitMQ didn't accept, which is
     * sent again later) is logged in one line instead of with a stack trace for every occurrence.
     */
    @Configuration
    static class AsyncErrorLogging implements AsyncConfigurer {

        private final BeanFactory beanFactory;

        AsyncErrorLogging(BeanFactory beanFactory) {
            this.beanFactory = beanFactory;
        }

        @Override
        public Executor getAsyncExecutor() {
            return beanFactory.getBean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME, Executor.class);
        }

        @Override
        public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
            return (failure, method, params) -> log.warn("Background task {}.{} failed: {}",
                    method.getDeclaringClass().getSimpleName(), method.getName(), failure.toString());
        }
    }
}
