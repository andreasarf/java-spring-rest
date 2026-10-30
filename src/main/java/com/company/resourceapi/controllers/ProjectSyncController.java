package com.company.resourceapi.controllers;

import static com.company.resourceapi.controllers.ProjectRestController.ENDPOINT;
import static com.company.resourceapi.controllers.ProjectRestController.ENDPOINT_ID;
import static com.company.resourceapi.controllers.ProjectRestController.PATH_VARIABLE_ID;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.services.ProjectService;
import com.company.resourceapi.services.ProjectSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ENDPOINT)
@Tag(name = "Project")
@RequiredArgsConstructor
@Slf4j
public class ProjectSyncController {

    private final ProjectService projectService;
    private final ProjectSyncService projectSyncService;

    @Operation(summary = "Refresh a Project's name from its SDLC system, in the background")
    @PostMapping(ENDPOINT_ID + "/sync")
    public ResponseEntity<Void> syncProject(
            @Parameter(name = PATH_VARIABLE_ID, description = "ID", required = true)
            @PathVariable(PATH_VARIABLE_ID) final long projectId) throws NotFoundException {
        // an unknown project is reported now, not only in the background
        projectService.getProject(projectId);
        projectSyncService.syncName(projectId);
        return ResponseEntity.accepted().build();
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Void> handleNotFound(NotFoundException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(NOT_FOUND).build();
    }
}
