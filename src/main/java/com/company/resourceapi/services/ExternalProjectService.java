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
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
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

    /**
     * Cached, including the answer "the SDLC system doesn't know this project" (cached as null), which
     * is kept only briefly so a project created there is found soon (see CacheConfig).
     */
    @Cacheable(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId")
    public Optional<ExternalProject> findProject(SdlcSystem sdlcSystem, String externalId) {
        return fetch(sdlcSystem, externalId);
    }

    /** Always asks the SDLC system and caches its answer, found or unknown, replacing the cached one. */
    @CachePut(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId")
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
