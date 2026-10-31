package com.company.resourceapi;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Gives every test application context a real Redis: one container, started on first use and
 * shared by all contexts of the test run (registered in META-INF/spring.factories).
 *
 * <p>Each context gets its own key prefix: contexts have their own in-memory database, so the same
 * id can mean different rows in two contexts and they must not see each other's cache entries.
 * A test can still point at another Redis with its own {@code spring.data.redis.*} properties.
 */
public class RedisTestContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        startOnce();
        var propertySources = context.getEnvironment().getPropertySources();
        // defaults: a test's own spring.data.redis.* properties win
        propertySources.addLast(new MapPropertySource("redisTestContainer", Map.of(
                "spring.data.redis.host", REDIS.getHost(),
                "spring.data.redis.port", REDIS.getMappedPort(6379))));
        // overrides application.properties
        propertySources.addFirst(new MapPropertySource("redisTestContainerKeyPrefix", Map.of(
                "spring.cache.redis.key-prefix", "test-" + UUID.randomUUID() + "::")));
    }

    private static synchronized void startOnce() {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
    }
}
