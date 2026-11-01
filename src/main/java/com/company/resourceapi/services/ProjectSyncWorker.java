package com.company.resourceapi.services;

import com.company.resourceapi.exceptions.NotFoundException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Refreshes a project's name from its SDLC system. Run by {@link ProjectSyncConsumer} for requests
 * queued by {@link ProjectSyncService#requestSync}, which makes sure only one runs per project.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectSyncWorker {

    private final ProjectService projectService;
    /** Bypasses the cache (a sync must see the current state) and refreshes it. */
    private final ExternalProjectService externalProjectService;

    /**
     * @throws NotFoundException if the project was deleted since the sync was requested
     * @throws org.springframework.web.client.RestClientException if the SDLC system failed or was unreachable
     * @throws com.company.resourceapi.exceptions.RateLimitExceededException if our own limit for calls to
     *     the SDLC system was reached
     */
    public void sync(long projectId) throws NotFoundException {
        var project = projectService.getProject(projectId);
        var externalId = project.getExternalId();
        var sdlcSystemId = project.getSdlcSystem().getId();

        var externalProject = externalProjectService.refreshProject(project.getSdlcSystem(), externalId);
        if (externalProject.isEmpty()) {
            log.warn("Project {} ({}) is unknown to its SDLC system", projectId, externalId);
            return;
        }
        var externalName = externalProject.get().name();
        if (externalName == null || externalName.equals(project.getName())) {
            log.info("Project {} name is unchanged in its SDLC system", projectId);
            return;
        }

        var applied = new AtomicBoolean();
        projectService.updateProject(projectId, stored -> {
            // the project may have been re-keyed while its SDLC system was being asked
            if (stored.getExternalId().equals(externalId) && stored.getSdlcSystem().getId() == sdlcSystemId) {
                stored.setName(externalName);
                applied.set(true);
            }
        });
        if (applied.get()) {
            log.info("Synced project {} name from its SDLC system", projectId);
        } else {
            log.info("Project {} changed while syncing; SDLC system name not applied", projectId);
        }
    }
}
