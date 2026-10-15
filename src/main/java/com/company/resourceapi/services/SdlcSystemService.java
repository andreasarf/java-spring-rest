package com.company.resourceapi.services;

import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.NotFoundException;

public interface SdlcSystemService {
    SdlcSystem getSdlcSystem(long id) throws NotFoundException;
}
