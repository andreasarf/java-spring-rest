package com.company.resourceapi.services.impl;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.exceptions.ConflictException;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.services.ProjectService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {
	private final ProjectRepository projectRepository;
	private final Validator validator;

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

	@Transactional(rollbackFor = NotFoundException.class)
	public Project updateProject(long id, ProjectUpdate update) throws NotFoundException {
		var project = getProject(id);
		var externalIdBefore = project.getExternalId();
		var sdlcSystemBefore = project.getSdlcSystem();
		update.applyTo(project);

		var violations = validator.validate(project);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}

		boolean keyChanged = !project.getExternalId().equals(externalIdBefore)
				|| project.getSdlcSystem().getId() != sdlcSystemBefore.getId();
		if (keyChanged && projectRepository
				.findByExternalIdAndSdlcSystemId(project.getExternalId(), project.getSdlcSystem().getId())
				.filter(existingProject -> existingProject.getId() != id)
				.isPresent()) {
			throw new ConflictException("Project %s already exists in SdlcSystem %d."
					.formatted(project.getExternalId(), project.getSdlcSystem().getId()));
		}

		// Managed entity: flushed on commit, only the changed columns (@DynamicUpdate)
		return project;
	}
}
