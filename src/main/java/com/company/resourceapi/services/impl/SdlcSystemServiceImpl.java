package com.company.resourceapi.services.impl;

import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.company.resourceapi.services.SdlcSystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SdlcSystemServiceImpl implements SdlcSystemService {

    private final SdlcSystemRepository sdlcSystemRepository;

    public SdlcSystem getSdlcSystem(long id) throws NotFoundException {
        return sdlcSystemRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(SdlcSystem.class, id));
    }
}
