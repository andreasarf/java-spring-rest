package com.company.resourceapi.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Redis-backed Spring Cache. Values are stored as JSON with their type, limited to the classes the
 * cached values consist of. A cache that can't be reached is logged and skipped, so Redis being down
 * makes requests slower, not fail.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheTtlProperties.class)
public class CacheConfig implements CachingConfigurer {

    public static final String SDLC_SYSTEMS = "sdlc-systems";
    public static final String EXTERNAL_PROJECTS = "external-projects";

    /**
     * Starts from Boot's defaults (spring.cache.redis.*: key prefix, null values, ...) and only
     * replaces the value serializer and sets each cache's time to live.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheCustomizer(CacheTtlProperties ttl) {
        var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.company.resourceapi.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.time.")
                .build();
        var serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                .build();
        return builder -> {
            var defaults = builder.cacheDefaults().serializeValuesWith(SerializationPair.fromSerializer(serializer));
            builder.cacheDefaults(defaults)
                    .withCacheConfiguration(SDLC_SYSTEMS, defaults.entryTtl(ttl.sdlcSystemsTtl()))
                    .withCacheConfiguration(EXTERNAL_PROJECTS, defaults.entryTtl(ttl.externalProjectsTtl()));
        };
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
