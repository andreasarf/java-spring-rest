package com.company.resourceapi.services;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.exceptions.NotFoundException;
import java.util.Optional;

public interface ProjectService {

    Project getProject(long id) throws NotFoundException;
    Project createProject(Project project);
    Optional<Project> getProject(String externalId, long sdlcSystemId);
    Optional<Project> getProjectStream(long id);
    Optional<Project> updateProject(Project updatedProject);
}
