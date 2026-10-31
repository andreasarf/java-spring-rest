package com.company.resourceapi.config;

import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Redis-backed Spring Cache. Values are stored as JSON with their type, limited to this
 * application's and the JDK's classes. A cache that can't be reached is logged and skipped, so
 * Redis being down makes requests slower, not fail.
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties({CacheProperties.class, CacheTtlProperties.class})
public class CacheConfig implements CachingConfigurer {

    public static final String SDLC_SYSTEMS = "sdlc-systems";
    public static final String EXTERNAL_PROJECTS = "external-projects";

    /**
     * Defining this bean replaces Boot's own, so Boot's spring.cache.redis.* settings are applied
     * here (otherwise e.g. the key prefix would silently be ignored).
     */
    @Bean
    RedisCacheConfiguration redisCacheConfiguration(CacheProperties cacheProperties) {
        var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.company.resourceapi.")
                .allowIfSubType("java.")
                .build();
        var serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(typeValidator)
                .build();
        var config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(SerializationPair.fromSerializer(serializer))
                .disableCachingNullValues();

        var redis = cacheProperties.getRedis();
        if (redis.getTimeToLive() != null) {
            config = config.entryTtl(redis.getTimeToLive());
        }
        if (redis.getKeyPrefix() != null) {
            config = config.prefixCacheNameWith(redis.getKeyPrefix());
        }
        if (!redis.isUseKeyPrefix()) {
            config = config.disableKeyPrefix();
        }
        return config;
    }

    @Bean
    RedisCacheManagerBuilderCustomizer cacheTimeToLive(RedisCacheConfiguration defaults, CacheTtlProperties ttl) {
        return builder -> builder
                .withCacheConfiguration(SDLC_SYSTEMS, defaults.entryTtl(ttl.sdlcSystemsTtl()))
                .withCacheConfiguration(EXTERNAL_PROJECTS, defaults.entryTtl(ttl.externalProjectsTtl()));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }
}
