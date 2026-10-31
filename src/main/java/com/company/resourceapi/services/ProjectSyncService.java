package com.company.resourceapi.services;

import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.exceptions.RateLimitExceededException;
import com.company.resourceapi.exceptions.RateLimitExceededException.Scope;
import com.company.resourceapi.exceptions.SyncInProgressException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Starts background syncs, at most one per project at a time across all instances. */
@Service
@RequiredArgsConstructor
public class ProjectSyncService {

    private final ProjectService projectService;
    private final ProjectSyncLock projectSyncLock;
    private final ProjectSyncWorker projectSyncWorker;
    private final RateLimiters rateLimiters;
    private final RateLimitProperties rateLimits;

    /**
     * Starts refreshing the project from its SDLC system in the background.
     *
     * @throws NotFoundException if the project doesn't exist
     * @throws RateLimitExceededException if the project was synced too often recently
     * @throws SyncInProgressException if a sync of the project is still running
     * @throws org.springframework.core.task.TaskRejectedException if no background slot is free
     */
    public void requestSync(long projectId) throws NotFoundException {
        // an unknown project is reported now, not only in the background
        projectService.getProject(projectId);

        var lease = projectSyncLock.tryAcquire(projectId)
                .orElseThrow(() -> new SyncInProgressException(projectId));

        // counted only for syncs that could run: an unknown project or a running sync doesn't use a permit
        var limit = rateLimits.sync();
        if (!rateLimiters.tryAcquire("sync-rate:" + projectId, limit)) {
            lease.release();
            throw new RateLimitExceededException(Scope.CALLER,
                    "Too many sync requests for project %d.".formatted(projectId), limit.retryAfterSeconds());
        }

        try {
            // the background task releases the lock when it ends, however it ends
            projectSyncWorker.syncName(projectId).whenComplete((result, failure) -> lease.release());
        } catch (RuntimeException e) {
            // not started, e.g. a full task executor
            lease.release();
            throw e;
        }
    }
}
