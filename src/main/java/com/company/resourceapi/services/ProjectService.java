package com.company.resourceapi.services;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.exceptions.NotFoundException;
import java.util.Optional;

public interface ProjectService {

    Project getProject(long id) throws NotFoundException;
    Project createProject(Project project);
    Optional<Project> getProject(String externalId, long sdlcSystemId);
    Optional<Project> getProjectStream(long id);

    /**
     * Applies {@code update} to the stored project and saves it in one transaction. The change is
     * rolled back if the update throws, the result violates the entity's constraints
     * ({@link jakarta.validation.ConstraintViolationException}) or another project in the same
     * SDLC system already has the external ID ({@link com.company.resourceapi.exceptions.ConflictException}).
     */
    Project updateProject(long id, ProjectUpdate update) throws NotFoundException;

    @FunctionalInterface
    interface ProjectUpdate {
        void applyTo(Project project) throws NotFoundException;
    }
}
