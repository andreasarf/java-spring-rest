package com.company.resourceapi.services;

import static java.util.concurrent.CompletableFuture.completedFuture;

import com.company.resourceapi.clients.SdlcProjectClient;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
            var externalName = sdlcProjectClient
                    .fetchProject(project.getSdlcSystem(), project.getExternalId())
                    .map(externalProject -> externalProject.name());
            if (externalName.isEmpty() || externalName.get().equals(project.getName())) {
                log.info("Project {} is unchanged in its SDLC system", projectId);
                return completedFuture(null);
            }
            projectService.updateProject(projectId, stored -> stored.setName(externalName.get()));
            log.info("Synced project {} name from its SDLC system", projectId);
            return completedFuture(null);
        } catch (Exception e) {
            log.warn("Syncing project {} from its SDLC system failed: {}", projectId, e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }
}
