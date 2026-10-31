package com.company.resourceapi.services;

import static com.company.resourceapi.config.CacheConfig.EXTERNAL_PROJECTS;
import static com.company.resourceapi.config.CacheConfig.MISSING_EXTERNAL_PROJECTS;

import com.company.resourceapi.clients.ExternalProject;
import com.company.resourceapi.clients.SdlcProjectClient;
import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.RateLimitExceededException;
import com.company.resourceapi.exceptions.RateLimitExceededException.Scope;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 * Projects as described by their SDLC system, cached per SDLC system and external id, and rate limited
 * per SDLC system across all instances.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalProjectService {

    private final SdlcProjectClient sdlcProjectClient;
    private final RateLimiters rateLimiters;
    private final RateLimitProperties rateLimits;
    private final CacheManager cacheManager;

    /**
     * Cached. A project unknown to the SDLC system is remembered separately and briefly, so repeated
     * lookups don't each call the SDLC system, while a project created there is still found soon.
     */
    @Cacheable(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId", unless = "#result == null")
    public Optional<ExternalProject> findProject(SdlcSystem sdlcSystem, String externalId) {
        var key = key(sdlcSystem, externalId);
        if (isKnownMissing(key)) {
            return Optional.empty();
        }
        var project = fetch(sdlcSystem, externalId);
        rememberMissing(key, project.isEmpty());
        return project;
    }

    /**
     * Always asks the SDLC system and updates the cache with the answer: a found project replaces the
     * cached one, a project the SDLC system no longer knows removes it.
     */
    @Caching(
            put = @CachePut(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId",
                    unless = "#result == null"),
            // unlike for @CachePut, #result is the Optional itself here (late evictions get the raw return value)
            evict = @CacheEvict(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId",
                    condition = "#result == null or #result.isEmpty()"))
    public Optional<ExternalProject> refreshProject(SdlcSystem sdlcSystem, String externalId) {
        var project = fetch(sdlcSystem, externalId);
        rememberMissing(key(sdlcSystem, externalId), project.isEmpty());
        return project;
    }

    // The "missing" cache is used directly, which bypasses the cache error handler: failures are
    // handled here the same way, by working without it.

    private boolean isKnownMissing(String key) {
        try {
            return missingCache().get(key) != null;
        } catch (RuntimeException e) {
            log.warn("Cache '{}' failed to get entry with key '{}': {}", MISSING_EXTERNAL_PROJECTS, key, e.getMessage());
            return false;
        }
    }

    private void rememberMissing(String key, boolean missing) {
        try {
            if (missing) {
                missingCache().put(key, Boolean.TRUE);
            } else {
                missingCache().evict(key);
            }
        } catch (RuntimeException e) {
            log.warn("Cache '{}' failed to update entry with key '{}': {}", MISSING_EXTERNAL_PROJECTS, key, e.getMessage());
        }
    }

    private Cache missingCache() {
        return cacheManager.getCache(MISSING_EXTERNAL_PROJECTS);
    }

    private static String key(SdlcSystem sdlcSystem, String externalId) {
        return sdlcSystem.getId() + ":" + externalId;
    }

    /** Only real calls count against the SDLC system's rate limit, not cache hits. */
    private Optional<ExternalProject> fetch(SdlcSystem sdlcSystem, String externalId) {
        var limit = rateLimits.sdlc();
        if (!rateLimiters.tryAcquire("sdlc-rate:" + sdlcSystem.getId(), limit)) {
            throw new RateLimitExceededException(Scope.UPSTREAM,
                    "Rate limit for SdlcSystem %d reached.".formatted(sdlcSystem.getId()), limit.retryAfterSeconds());
        }
        return sdlcProjectClient.fetchProject(sdlcSystem, externalId);
    }
}
