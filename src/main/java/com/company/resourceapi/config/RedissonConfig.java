package com.company.resourceapi.config;

import java.time.Duration;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.NameMapper;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    RedissonClient redissonClient(DataRedisConnectionDetails connection, DataRedisProperties redis,
            @Value("${resourceapi.redisson.key-prefix}") String keyPrefix) {
        var standalone = connection.getStandalone();
        if (standalone == null) {
            throw new IllegalStateException("Only a standalone Redis is supported for Redisson");
        }
        var config = new Config().setLazyInitialization(true);
        config.useSingleServer()
                .setAddress((connection.getSslBundle() != null ? "rediss://" : "redis://")
                        + standalone.getHost() + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase())
                .setUsername(connection.getUsername())
                .setPassword(connection.getPassword())
                .setConnectTimeout(millis(redis.getConnectTimeout(), Duration.ofSeconds(10)))
                .setTimeout(millis(redis.getTimeout(), Duration.ofSeconds(3)))
                // fail fast like the cache: callers decide what to do without Redis
                .setRetryAttempts(1)
                .setNameMapper(new KeyPrefixNameMapper(keyPrefix));
        return Redisson.create(config);
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
