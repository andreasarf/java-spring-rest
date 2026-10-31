package com.company.resourceapi.services;

import static com.company.resourceapi.config.CacheConfig.EXTERNAL_PROJECTS;

import com.company.resourceapi.clients.ExternalProject;
import com.company.resourceapi.clients.SdlcProjectClient;
import com.company.resourceapi.entities.SdlcSystem;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Projects as described by their SDLC system, cached per SDLC system and external id. */
@Service
@RequiredArgsConstructor
public class ExternalProjectService {

    private final SdlcProjectClient sdlcProjectClient;

    /** Cached; a project unknown to the SDLC system isn't cached, so it is found once it exists. */
    @Cacheable(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId", unless = "#result == null")
    public Optional<ExternalProject> findProject(SdlcSystem sdlcSystem, String externalId) {
        return sdlcProjectClient.fetchProject(sdlcSystem, externalId);
    }

    /** Always asks the SDLC system, and puts the fresh answer in the cache. */
    @CachePut(cacheNames = EXTERNAL_PROJECTS, key = "#sdlcSystem.id + ':' + #externalId", unless = "#result == null")
    public Optional<ExternalProject> refreshProject(SdlcSystem sdlcSystem, String externalId) {
        return sdlcProjectClient.fetchProject(sdlcSystem, externalId);
    }
}
