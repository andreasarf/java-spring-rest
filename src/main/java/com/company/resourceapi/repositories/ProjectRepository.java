package com.company.resourceapi.repositories;

import com.company.resourceapi.entities.Project;
import jakarta.persistence.QueryHint;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long>{

    /** Reads the project and locks its row until the transaction ends (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findForUpdateById(@Param("id") long id);

    Optional<Project> findBySdlcSystemIdAndId(long sdlcSystemId, long projectId);
    Optional<Project> findByExternalIdAndSdlcSystemId(String externalId, long sdlcSystemId);

    /**
     * Finds another project with the same key. Doesn't flush pending changes first, so it can
     * check a modified project before that change is written.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    boolean existsByExternalIdAndSdlcSystemIdAndIdNot(String externalId, long sdlcSystemId, long id);
}
