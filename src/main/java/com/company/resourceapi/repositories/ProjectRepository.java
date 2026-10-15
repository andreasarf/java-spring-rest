package com.company.resourceapi.repositories;

import com.company.resourceapi.entities.Project;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long>{

    Optional<Project> findBySdlcSystemIdAndId(long sdlcSystemId, long projectId);

    /** Doesn't flush pending changes first, so it can check a modified project before it is saved. */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    Optional<Project> findByExternalIdAndSdlcSystemId(String externalId, long sdlcSystemId);
}
