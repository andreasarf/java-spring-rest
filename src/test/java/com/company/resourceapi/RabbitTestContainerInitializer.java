package com.company.resourceapi;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Gives every test application context a real RabbitMQ: one container, started on first use and
 * shared by all contexts of the test run (registered in META-INF/spring.factories).
 *
 * <p>Each context gets its own notifications exchange: contexts have their own in-memory database,
 * so the same project id can be sent by two contexts. A test can still point at another broker with
 * its own {@code spring.rabbitmq.*} properties.
 */
public class RabbitTestContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final GenericContainer<?> RABBITMQ =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:4-alpine"))
                    .withExposedPorts(5672)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        startOnce();
        var propertySources = context.getEnvironment().getPropertySources();
        // defaults: a test's own spring.rabbitmq.* properties win
        propertySources.addLast(new MapPropertySource("rabbitTestContainer", Map.of(
                "spring.rabbitmq.host", RABBITMQ.getHost(),
                "spring.rabbitmq.port", RABBITMQ.getMappedPort(5672))));
        // overrides application.properties
        propertySources.addFirst(new MapPropertySource("rabbitTestContainerExchange", Map.of(
                "resourceapi.notifications.exchange", "test-" + UUID.randomUUID())));
    }

    private static synchronized void startOnce() {
        if (!RABBITMQ.isRunning()) {
            RABBITMQ.start();
        }
    }
}
