package com.company.resourceapi.services.impl;

import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.company.resourceapi.services.SdlcSystemService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SdlcSystemServiceImpl implements SdlcSystemService {

    private final SdlcSystemRepository sdlcSystemRepository;

    public Optional<SdlcSystem> getSdlcSystem(long id) {
        return sdlcSystemRepository.findById(id);
    }
}
