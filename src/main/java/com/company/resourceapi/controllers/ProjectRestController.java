package com.company.resourceapi.controllers;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.OK;
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
import java.net.URI;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
            @Parameter(name = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) final long projectId) {
        try {
            return ResponseEntity.status(OK).body(projectService.getProject(projectId));
        } catch (NotFoundException e) {
            log.debug(e.getMessage());
            return ResponseEntity.status(NOT_FOUND).build();
        }
    }

    @Operation(summary = "Create a Project")
    @PostMapping
    public ResponseEntity<Project> createProject(
            @Valid
            @RequestBody Project project) {
        long systemId = project.getSdlcSystem().getId();

        if (sdlcSystemService.getSdlcSystem(systemId).isEmpty()) {
            return ResponseEntity.status(NOT_FOUND).build();
        }
        if (projectService.getProject(project.getExternalId(), systemId).isPresent()) {
            return ResponseEntity.status(CONFLICT).build();
        }

        var createdProject = projectService.createProject(project);
        return ResponseEntity.status(CREATED)
                .location(URI.create(LOCATION_HEADER.formatted(createdProject.getId())))
                .body(createdProject);
    }

    @Operation(summary = "Update a Project")
    @PatchMapping(ENDPOINT_ID)
    public ResponseEntity<Project> updateProject(
            @Parameter(name = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) long projectId,
            @Valid @RequestBody PatchedProject patchedProject) {

        try {
            var project = projectService.getProject(projectId);

            if (patchedProject.isNameDirty()) {
                project.setName(patchedProject.getName());
            }

            if (patchedProject.isExternalIdDirty()) {
                project.setExternalId(patchedProject.getExternalId());
            }

            if (patchedProject.isSdlcSystemDirty()) {
                long systemId = patchedProject.getSdlcSystem().getId();
                if (sdlcSystemService.getSdlcSystem(systemId).isPresent()) {
                    project.setSdlcSystem(patchedProject.getSdlcSystem());
                } else {
                    throw new NotFoundException(SdlcSystem.class, systemId);
                }
            }

            if ((patchedProject.isExternalIdDirty() || patchedProject.isSdlcSystemDirty())
                    && projectService
                    .getProject(project.getExternalId(), project.getSdlcSystem().getId())
                    .isPresent()) {
                return ResponseEntity.status(CONFLICT).build();
            }

            return projectService.updateProject(project)
                    .map(resultantProject -> ResponseEntity.status(OK).body(resultantProject))
                    .orElseGet(() -> ResponseEntity.status(BAD_REQUEST).build());
        } catch (NotFoundException e) {
            log.debug(e.getMessage());
            return ResponseEntity.status(NOT_FOUND).build();
        }
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
    }
}
