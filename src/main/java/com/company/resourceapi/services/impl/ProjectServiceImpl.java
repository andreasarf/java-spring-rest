package com.company.resourceapi.services.impl;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.ProjectETag;
import com.company.resourceapi.events.ProjectCreated;
import com.company.resourceapi.events.ProjectUpdated;
import com.company.resourceapi.events.ProjectUpdated.Field;
import com.company.resourceapi.exceptions.ConflictException;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.exceptions.PreconditionFailedException;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.services.ProjectService;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {
	private final ProjectRepository projectRepository;
	private final Validator validator;
	private final ApplicationEventPublisher events;

	public Project getProject(long id) throws NotFoundException {
		return getProjectStream(id).orElseThrow(() -> new NotFoundException(Project.class, id));
	}

	@Transactional
	public Project createProject(Project project) {
		var created = projectRepository.save(project);
		events.publishEvent(new ProjectCreated(created.getId(), created.getSdlcSystem().getId(),
				created.getExternalId(), created.getName(), Instant.now()));
		return created;
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
		return updateProject(id, null, update);
	}

	@Transactional(rollbackFor = NotFoundException.class)
	public Project updateProject(long id, String ifMatch, ProjectUpdate update) throws NotFoundException {
		Project project;
		if (ifMatch == null) {
			project = getProject(id);
		} else {
			project = projectRepository.findForUpdateById(id)
					.orElseThrow(() -> new NotFoundException(Project.class, id));
			if (!ProjectETag.matches(ifMatch, project)) {
				throw new PreconditionFailedException(id);
			}
		}
		var externalIdBefore = project.getExternalId();
		var sdlcSystemBefore = project.getSdlcSystem();
		var nameBefore = project.getName();
		update.applyTo(project);

		var violations = validator.validate(project);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}

		boolean keyChanged = !project.getExternalId().equals(externalIdBefore)
				|| project.getSdlcSystem().getId() != sdlcSystemBefore.getId();
		if (keyChanged && projectRepository.existsByExternalIdAndSdlcSystemIdAndIdNot(
				project.getExternalId(), project.getSdlcSystem().getId(), id)) {
			throw new ConflictException("Project %s already exists in SdlcSystem %d."
					.formatted(project.getExternalId(), project.getSdlcSystem().getId()));
		}

		var changed = EnumSet.noneOf(Field.class);
		if (!Objects.equals(project.getName(), nameBefore)) {
			changed.add(Field.NAME);
		}
		if (!project.getExternalId().equals(externalIdBefore)) {
			changed.add(Field.EXTERNAL_ID);
		}
		if (project.getSdlcSystem().getId() != sdlcSystemBefore.getId()) {
			changed.add(Field.SDLC_SYSTEM);
		}
		if (!changed.isEmpty()) {
			events.publishEvent(new ProjectUpdated(id, project.getSdlcSystem().getId(),
					project.getExternalId(), project.getName(), changed, Instant.now()));
		}

		// Managed entity: flushed on commit, only the changed columns (@DynamicUpdate)
		return project;
	}
}
