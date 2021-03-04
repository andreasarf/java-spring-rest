package com.company.resourceapi.repositories;

import com.company.resourceapi.entities.SdlcSystem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SdlcSystemRepository extends JpaRepository<SdlcSystem, Long>{

}
