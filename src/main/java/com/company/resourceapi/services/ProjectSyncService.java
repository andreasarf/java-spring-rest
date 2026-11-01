package com.company.resourceapi.services;

import com.company.resourceapi.config.ProjectSyncQueueConfig.SyncQueueProperties;
import com.company.resourceapi.config.RateLimitProperties;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.exceptions.RateLimitExceededException;
import com.company.resourceapi.exceptions.RateLimitExceededException.Scope;
import com.company.resourceapi.exceptions.SyncInProgressException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/** Queues syncs, at most one per project at a time across all instances. */
@Service
@RequiredArgsConstructor
public class ProjectSyncService {

    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final ProjectService projectService;
    private final ProjectSyncLock projectSyncLock;
    private final RateLimiters rateLimiters;
    private final RateLimitProperties rateLimits;
    private final RabbitTemplate rabbitTemplate;
    private final SyncQueueProperties syncQueue;

    /**
     * Queues refreshing the project from its SDLC system; {@link ProjectSyncConsumer} runs it.
     *
     * @throws NotFoundException if the project doesn't exist
     * @throws RateLimitExceededException if the project was synced too often recently
     * @throws SyncInProgressException if a sync of the project is queued or running
     * @throws AmqpException if the request couldn't be queued (RabbitMQ unreachable or refusing it)
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
            // the consumer releases the lock when the sync ends, however it ends
            var request = new ProjectSyncRequest(projectId, lease.ownerIdOrNull());
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend("", syncQueue.queue(), request);
                // accepted (202) only once the broker has the request
                operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT.toMillis());
                return null;
            });
        } catch (AmqpException e) {
            lease.release();
            throw e;
        }
    }
}
