package com.company.resourceapi.services;

import static com.company.resourceapi.config.CacheConfig.EXTERNAL_PROJECTS;

import com.company.resourceapi.clients.ExternalProject;
import com.company.resourceapi.clients.SdlcProjectClient;
import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.RateLimitExceededException;
import com.company.resourceapi.exceptions.RateLimitExceededException.Scope;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
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
public class ExternalProjectService {

    private final SdlcProjectClient sdlcProjectClient;
    private final RateLimiters rateLimiters;
    private final RateLimitProperties rateLimits;

    /** Cached; a project unknown to the SDLC system isn't cached, so it is found once it exists. */
    @Cacheable(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId", unless = "#result == null")
    public Optional<ExternalProject> findProject(SdlcSystem sdlcSystem, String externalId) {
        return fetch(sdlcSystem, externalId);
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
        return fetch(sdlcSystem, externalId);
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
