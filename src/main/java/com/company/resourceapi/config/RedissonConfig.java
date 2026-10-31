package com.company.resourceapi.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.NameMapper;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson client for distributed locks and rate limiters, connected to the same Redis as the cache
 * (Boot's connection details, so Docker Compose and Testcontainers connections work too).
 *
 * <p>It connects lazily, on first use: the application starts without Redis (for example the AOT
 * training run while building an image), and Redis being down only affects the calls that need it.
 * Only a standalone Redis is supported; with Sentinel or Cluster there is no client and its users fall
 * back to running without it. TLS uses the JVM's trust store (Boot SSL bundles aren't passed on).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RedissonConfig {

    static final String REDISSON_CLIENT = "redissonClient";
    static final String TASK_EXECUTOR = "applicationTaskExecutor";

    @Bean(name = REDISSON_CLIENT, destroyMethod = "shutdown")
    @ConditionalOnExpression("'${spring.data.redis.sentinel.master:}' == '' and '${spring.data.redis.cluster.nodes:}' == ''")
    RedissonClient redissonClient(DataRedisConnectionDetails connection, DataRedisProperties redis,
            @Value("${resourceapi.redisson.key-prefix}") String keyPrefix) {
        var standalone = connection.getStandalone();
        var config = new Config()
                .setLazyInitialization(true)
                .setUsername(connection.getUsername())
                .setPassword(connection.getPassword())
                .setNameMapper(new KeyPrefixNameMapper(keyPrefix));
        config.useSingleServer()
                .setAddress((connection.getSslBundle() != null ? "rediss://" : "redis://")
                        + standalone.getHost() + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase())
                .setConnectTimeout(millis(redis.getConnectTimeout(), Duration.ofSeconds(10)))
                .setTimeout(millis(redis.getTimeout(), Duration.ofSeconds(3)))
                // fail fast like the cache: callers decide what to do without Redis
                .setRetryAttempts(0)
                // a lock or rate limit check now and then needs few connections (defaults: 24 idle, 64 max)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(4);
        return Redisson.create(config);
    }

    /**
     * On shutdown, the task executor waits for running syncs, which release their locks when they end;
     * Redisson must still be up then, so the executor depends on it and is destroyed first.
     */
    @Bean
    static BeanFactoryPostProcessor taskExecutorShutsDownBeforeRedisson() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition(TASK_EXECUTOR) && beanFactory.containsBeanDefinition(REDISSON_CLIENT)) {
                var executor = beanFactory.getBeanDefinition(TASK_EXECUTOR);
                var dependsOn = executor.getDependsOn() == null ? new String[0] : executor.getDependsOn();
                executor.setDependsOn(Stream.concat(Arrays.stream(dependsOn), Stream.of(REDISSON_CLIENT))
                        .distinct().toArray(String[]::new));
            }
        };
    }

    private static int millis(Duration value, Duration fallback) {
        return (int) (value != null ? value : fallback).toMillis();
    }

    /** Prefixes every Redisson object name (locks, rate limiters) in Redis. */
    record KeyPrefixNameMapper(String prefix) implements NameMapper {

        @Override
        public String map(String name) {
            return prefix + name;
        }

        @Override
        public String unmap(String name) {
            return name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        }
    }
}
