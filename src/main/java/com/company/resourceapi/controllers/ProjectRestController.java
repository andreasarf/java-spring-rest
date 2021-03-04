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
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import java.net.URI;
import java.util.Optional;
import javax.validation.Valid;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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
@Api(produces = MediaType.APPLICATION_JSON_VALUE, tags = "Project")
public class ProjectRestController {

    public static final String ENDPOINT = "/api/v2/projects";
    public static final String ENDPOINT_ID = "/{id}";
    public static final String PATH_VARIABLE_ID = "id";

    private static final String API_PARAM_ID = "ID";
    private static final String LOCATION_HEADER = ENDPOINT + "/%d";

    @Autowired
    private ProjectService projectService;

    @Autowired
    private SdlcSystemService sdlcSystemService;

    @ApiOperation("Get a Project")
    @GetMapping(ENDPOINT_ID)
    public ResponseEntity<Project> getProject(
            @ApiParam(name = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) final long projectId) {
        Project project;
        try {
            project = projectService.getProject(projectId);
        } catch (NotFoundException e) {
            System.err.println(e.getMessage());
            return ResponseEntity.status(NOT_FOUND).build();
        }

        return ResponseEntity.status(OK).body(project);
    }

    @ApiOperation("Create a Project")
    @PostMapping
    public ResponseEntity<Project> createProject(
            @Valid
            @RequestBody Project project) {

        System.out.println(project.getSdlcSystem());

        if (sdlcSystemService.getSdlcSystem(project.getSdlcSystem().getId()).isPresent()) {
            if (projectService.getProject(project.getExternalId(), project.getSdlcSystem().getId())
                    .isPresent()) {
                return ResponseEntity.status(CONFLICT).build();
            } else {
                return Optional.of(projectService.createProject(project))
                        .map(resultantProject -> ResponseEntity.status(CREATED).location(
                                URI.create(
                                        String.format(LOCATION_HEADER, resultantProject.getId())))
                                .body(resultantProject))
                        .orElse(ResponseEntity.status(BAD_REQUEST).build());
            }
        } else {
            return ResponseEntity.status(NOT_FOUND).build();
        }
    }

    @ApiOperation("Update a Project")
    @PatchMapping(ENDPOINT_ID)
    public ResponseEntity<Project> updateProject(
            @ApiParam(name = API_PARAM_ID, required = true)
            @PathVariable(PATH_VARIABLE_ID) long projectId,
            @Valid @RequestBody PatchedProject patchedProject) {

        try {
            Project project = projectService.getProject(projectId);

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
            } else {
                return projectService.updateProject(project)
                        .map(resultantProject -> ResponseEntity.status(OK).body(resultantProject))
                        .orElse(ResponseEntity.status(BAD_REQUEST).build());
            }
        } catch (NotFoundException e) {
            System.err.println(e.getMessage());
        }

        return ResponseEntity.status(NOT_FOUND).build();
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
