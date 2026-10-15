package com.company.resourceapi.services.impl;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.services.ProjectService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {
	private final ProjectRepository projectRepository;

	public Project getProject(long id) throws NotFoundException {
		return getProjectStream(id).orElseThrow(() -> new NotFoundException(Project.class, id));
	}

	public Project createProject(Project project) {
		return projectRepository.save(project);
	}

	public Optional<Project> getProject(String externalId, long sdlcSystemId) {
		return projectRepository
				.findByExternalIdAndSdlcSystemId(externalId, sdlcSystemId);
	}

	public Optional<Project> getProjectStream(long id) {
		return projectRepository.findById(id);
	}

	public Project updateProject(Project updatedProject) {
		return projectRepository.save(updatedProject);
	}
}
