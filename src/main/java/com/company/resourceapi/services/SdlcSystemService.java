package com.company.resourceapi.services;

import com.company.resourceapi.entities.SdlcSystem;
import java.util.Optional;

public interface SdlcSystemService {
    Optional<SdlcSystem> getSdlcSystem(long id);
}
