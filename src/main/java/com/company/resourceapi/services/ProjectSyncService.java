package com.company.resourceapi.services;

import static java.util.concurrent.CompletableFuture.completedFuture;

import com.company.resourceapi.clients.SdlcProjectClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/** Example of background work: refreshes a project's name from its SDLC system. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectSyncService {

    private final ProjectService projectService;
    private final SdlcProjectClient sdlcProjectClient;

    /**
     * Runs on a virtual thread of the application task executor, in the caller's trace. Failures are
     * logged here, since nobody waits for the result of a fire-and-forget call.
     *
     * @return completes when the sync has finished, exceptionally if it failed
     */
    @Async
    public CompletableFuture<Void> syncName(long projectId) {
        try {
            var project = projectService.getProject(projectId);
            var externalId = project.getExternalId();
            var sdlcSystemId = project.getSdlcSystem().getId();

            var externalProject = sdlcProjectClient.fetchProject(project.getSdlcSystem(), externalId);
            if (externalProject.isEmpty()) {
                log.warn("Project {} ({}) is unknown to its SDLC system", projectId, externalId);
                return completedFuture(null);
            }
            var externalName = externalProject.get().name();
            if (externalName == null || externalName.equals(project.getName())) {
                log.info("Project {} name is unchanged in its SDLC system", projectId);
                return completedFuture(null);
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
            return completedFuture(null);
        } catch (Exception e) {
            // the status only: an error response's message would include the upstream body
            var reason = e instanceof RestClientResponseException response
                    ? "HTTP " + response.getStatusCode().value()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Syncing project {} from its SDLC system failed: {}", projectId, reason);
            return CompletableFuture.failedFuture(e);
        }
    }
}
