package com.company.resourceapi.services.impl;

import static com.company.resourceapi.config.CacheConfig.SDLC_SYSTEMS;

import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.company.resourceapi.services.SdlcSystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SdlcSystemServiceImpl implements SdlcSystemService {

    private final SdlcSystemRepository sdlcSystemRepository;

    /**
     * Cached: SDLC systems are read on every create, update and SDLC call, and rarely change. When an
     * entry expires, concurrent callers wait for one database read instead of each doing their own.
     */
    @Cacheable(cacheNames = SDLC_SYSTEMS, sync = true)
    public SdlcSystem getSdlcSystem(long id) throws NotFoundException {
        return sdlcSystemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(SdlcSystem.class, id));
    }
}
