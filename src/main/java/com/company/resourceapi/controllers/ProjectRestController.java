package com.company.resourceapi.controllers;

import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.company.resourceapi.clients.ExternalProject;
import com.company.resourceapi.clients.SdlcProjectClient;
import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.ConflictException;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.services.ProjectService;
import com.company.resourceapi.services.SdlcSystemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

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
    private final SdlcProjectClient sdlcProjectClient;

    @Operation(summary = "Get a Project")
    @GetMapping(ENDPOINT_ID)
    public ResponseEntity<Project> getProject(
            @Parameter(name = PATH_VARIABLE_ID, description = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) final long projectId) throws NotFoundException {
        return ResponseEntity.ok(projectService.getProject(projectId));
    }

    @Operation(summary = "Get a Project as described by its SDLC system")
    @GetMapping(ENDPOINT_ID + "/external")
    public ResponseEntity<ExternalProject> getExternalProject(
            @Parameter(name = PATH_VARIABLE_ID, description = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) final long projectId) throws NotFoundException {
        var project = projectService.getProject(projectId);
        return sdlcProjectClient.fetchProject(project.getSdlcSystem(), project.getExternalId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(NOT_FOUND).build());
    }

    @Operation(summary = "Create a Project")
    @PostMapping
    public ResponseEntity<Project> createProject(
            @Valid
            @RequestBody Project project) throws NotFoundException {
        var sdlcSystem = findSdlcSystem(project.getSdlcSystem());

        // Checked up front for a clean 409; the unique key still guards against concurrent requests
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
            @RequestBody PatchedProject patchedProject) throws NotFoundException {
        var updatedProject = projectService.updateProject(projectId, project -> {
            if (patchedProject.isNameDirty()) {
                project.setName(patchedProject.getName());
            }

            if (patchedProject.isExternalIdDirty()) {
                project.setExternalId(patchedProject.getExternalId());
            }

            if (patchedProject.isSdlcSystemDirty()) {
                var patchedSystem = patchedProject.getSdlcSystem();
                project.setSdlcSystem(patchedSystem == null ? null : findSdlcSystem(patchedSystem));
            }
        });

        return ResponseEntity.ok(updatedProject);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Void> handleConstraintViolation(ConstraintViolationException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(BAD_REQUEST).build();
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Void> handleNotFound(NotFoundException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(NOT_FOUND).build();
    }

    /** The SDLC system answered with an error, or couldn't be reached in time. */
    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Void> handleSdlcSystemFailure(RestClientException e) {
        log.warn("SDLC system request failed: {}", e.getMessage());
        return ResponseEntity.status(BAD_GATEWAY).build();
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Void> handleConflict(ConflictException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(CONFLICT).build();
    }

    /**
     * The (sdlcSystem, externalId) pair is unique, so a concurrent request can still insert a
     * duplicate after the conflict checks have passed. Any other integrity violation is a
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
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return violation.getKind() == ConstraintKind.UNIQUE;
            }
        }
        return false;
    }

    /** Resolves the SDLC system a request refers to; an absent id deserializes to 0. */
    private SdlcSystem findSdlcSystem(SdlcSystem reference) throws NotFoundException {
        if (reference.getId() == 0) {
            throw new ResponseStatusException(BAD_REQUEST, "sdlcSystem.id is required");
        }
        return sdlcSystemService.getSdlcSystem(reference.getId());
    }

    @Getter
    @NoArgsConstructor
    static class PatchedProject {
        private String externalId;
        private String name;
        private SdlcSystem sdlcSystem;
        private boolean isNameDirty = false;
        private boolean isExternalIdDirty = false;
        private boolean isSdlcSystemDirty = false;

        public void setExternalId(String externalId) {
            this.externalId = externalId;
            isExternalIdDirty = true;
        }

        public void setName(String name) {
            this.name = name;
            isNameDirty = true;
        }

        public void setSdlcSystem(SdlcSystem sdlcSystem) {
            this.sdlcSystem = sdlcSystem;
            isSdlcSystemDirty = true;
        }
    }
}
