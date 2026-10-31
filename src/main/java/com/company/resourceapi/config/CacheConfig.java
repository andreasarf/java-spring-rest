package com.company.resourceapi.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
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
public class CacheConfig {

    public static final String SDLC_SYSTEMS = "sdlc-systems";
    public static final String EXTERNAL_PROJECTS = "external-projects";
    public static final String MISSING_EXTERNAL_PROJECTS = "external-projects-missing";

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
                    .withCacheConfiguration(EXTERNAL_PROJECTS, defaults.entryTtl(ttl.externalProjectsTtl()))
                    .withCacheConfiguration(MISSING_EXTERNAL_PROJECTS, defaults.entryTtl(ttl.missingExternalProjectsTtl()));
        };
    }

    /**
     * Caching infrastructure reads this before regular beans exist; as an infrastructure bean of its
     * own, without dependencies, that early creation is expected and not reported as a problem.
     */
    @Configuration
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static class CacheErrorHandling implements CachingConfigurer {

        @Override
        public CacheErrorHandler errorHandler() {
            return new LoggingCacheErrorHandler();
        }
    }
}
