package com.company.resourceapi.services;

import com.company.resourceapi.events.ProjectCreated;
import com.company.resourceapi.events.ProjectUpdated;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fetches a project's name from its SDLC system when the project is created or pointed at another
 * project there, so clients don't have to request a sync themselves.
 *
 * <p>Runs after the change is committed (a rolled-back change starts nothing), on the request
 * thread, which only starts the sync: the sync itself runs in the background. A sync that can't start
 * now (one is running, rate limited, no free slot) is logged and skipped; it can be requested later.
 */
@Component
@ConditionalOnBooleanProperty(name = "resourceapi.sync.on-change", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SyncOnProjectChange {

    private final ProjectSyncService projectSyncService;

    @TransactionalEventListener
    void onCreated(ProjectCreated event) {
        requestSync(event.projectId());
    }

    @TransactionalEventListener(condition = "#event.rekeyed")
    void onRekeyed(ProjectUpdated event) {
        requestSync(event.projectId());
    }

    private void requestSync(long projectId) {
        try {
            projectSyncService.requestSync(projectId);
        } catch (Exception e) {
            log.info("Sync of project {} after its change not started: {}", projectId, e.getMessage());
        }
    }
}
