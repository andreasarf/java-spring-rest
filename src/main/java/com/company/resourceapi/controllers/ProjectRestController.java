package com.company.resourceapi.controllers;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.services.ProjectService;
import com.company.resourceapi.services.SdlcSystemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ProjectRestController.ENDPOINT)
@Tag(name = "Project")
@RequiredArgsConstructor
@Slf4j
public class ProjectRestController {

    public static final String ENDPOINT = "/api/v2/projects";
    public static final String ENDPOINT_ID = "/{id}";
    public static final String PATH_VARIABLE_ID = "id";

    private static final String API_PARAM_ID = "ID";
    private static final String LOCATION_HEADER = ENDPOINT + "/%d";

    private final ProjectService projectService;
    private final SdlcSystemService sdlcSystemService;

    @Operation(summary = "Get a Project")
    @GetMapping(ENDPOINT_ID)
    public ResponseEntity<Project> getProject(
            @Parameter(name = PATH_VARIABLE_ID, description = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) final long projectId) throws NotFoundException {
        return ResponseEntity.ok(projectService.getProject(projectId));
    }

    @Operation(summary = "Create a Project")
    @PostMapping
    public ResponseEntity<Project> createProject(
            @Valid
            @RequestBody Project project) throws NotFoundException {
        var sdlcSystem = findSdlcSystem(project.getSdlcSystem().getId());

        if (projectService.getProject(project.getExternalId(), sdlcSystem.getId()).isPresent()) {
            return ResponseEntity.status(CONFLICT).build();
        }

        project.setSdlcSystem(sdlcSystem);
        var createdProject = projectService.createProject(project);
        return ResponseEntity.status(CREATED)
                .location(URI.create(LOCATION_HEADER.formatted(createdProject.getId())))
                .body(createdProject);
    }

    @Operation(summary = "Update a Project")
    @PatchMapping(ENDPOINT_ID)
    public ResponseEntity<Project> updateProject(
            @Parameter(name = PATH_VARIABLE_ID, description = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) long projectId,
            @Valid @RequestBody PatchedProject patchedProject) throws NotFoundException {
        var project = projectService.getProject(projectId);

        if (patchedProject.isNameDirty()) {
            project.setName(patchedProject.getName());
        }

        if (patchedProject.isExternalIdDirty()) {
            project.setExternalId(patchedProject.getExternalId());
        }

        if (patchedProject.isSdlcSystemDirty()) {
            project.setSdlcSystem(findSdlcSystem(patchedProject.getSdlcSystem().getId()));
        }

        if ((patchedProject.isExternalIdDirty() || patchedProject.isSdlcSystemDirty())
                && projectService
                .getProject(project.getExternalId(), project.getSdlcSystem().getId())
                .filter(existingProject -> existingProject.getId() != projectId)
                .isPresent()) {
            return ResponseEntity.status(CONFLICT).build();
        }

        return ResponseEntity.ok(projectService.updateProject(project));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Void> handleNotFound(NotFoundException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(NOT_FOUND).build();
    }

    /**
     * The (sdlcSystem, externalId) pair is unique, so a concurrent request can still insert a
     * duplicate after the conflict check above has passed. Any other integrity violation is a
     * server-side problem and is left to the default error handling.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Void> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        if (!isUniqueConstraintViolation(e)) {
            throw e;
        }
        log.debug("Rejected duplicate project: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(CONFLICT).build();
    }

    static boolean isUniqueConstraintViolation(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && violation.getKind() == ConstraintKind.UNIQUE;
    }

    private SdlcSystem findSdlcSystem(long systemId) throws NotFoundException {
        return sdlcSystemService.getSdlcSystem(systemId)
                .orElseThrow(() -> new NotFoundException(SdlcSystem.class, systemId));
    }

    @Getter
    @NoArgsConstructor
    static class PatchedProject {
        @Size(max = Project.MAX_TEXT_LENGTH)
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        private String externalId;
        @Size(max = Project.MAX_TEXT_LENGTH)
        private String name;
        private SdlcSystem sdlcSystem;
        private boolean isNameDirty = false;
        private boolean isExternalIdDirty = false;
        private boolean isSdlcSystemDirty = false;

        public void setExternalId(String externalId) {
            if (externalId != null) {
                this.externalId = externalId;
                isExternalIdDirty = true;
            }
        }

        public void setName(String name) {
            this.name = name;
            isNameDirty = true;
        }

        public void setSdlcSystem(SdlcSystem sdlcSystem) {
            this.sdlcSystem = sdlcSystem;
            isSdlcSystemDirty = true;
        }

        /** A project always belongs to an SDLC system, so it can be replaced but not removed. */
        @AssertTrue(message = "sdlcSystem must not be null")
        boolean isSdlcSystemPresentIfPatched() {
            return !isSdlcSystemDirty || sdlcSystem != null;
        }
    }
}
