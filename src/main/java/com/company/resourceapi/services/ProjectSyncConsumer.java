package com.company.resourceapi.services;

import com.company.resourceapi.config.ProjectSyncQueueConfig.SyncQueueProperties;
import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.exceptions.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Runs queued syncs ({@code spring.rabbitmq.listener.simple.*}: virtual threads, in the trace of the
 * request that queued them). A sync failing for a reason that may pass is retried with a growing
 * delay; one that still fails goes to the dead-letter queue. The project's sync lock is released
 * however the sync ends.
 */
@Component
@Slf4j
public class ProjectSyncConsumer {

    private final ProjectSyncWorker projectSyncWorker;
    private final ProjectSyncLock projectSyncLock;
    private final RetryTemplate retryTemplate;

    public ProjectSyncConsumer(ProjectSyncWorker projectSyncWorker, ProjectSyncLock projectSyncLock,
            SyncQueueProperties properties) {
        this.projectSyncWorker = projectSyncWorker;
        this.projectSyncLock = projectSyncLock;
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(properties.retryDelay())
                .multiplier(properties.retryMultiplier())
                .predicate(ProjectSyncConsumer::mayPass)
                .build());
    }

    @RabbitListener(queues = "${resourceapi.sync.queue:resourceapi.project-sync}")
    public void onSyncRequested(ProjectSyncRequest request) {
        long projectId = request.projectId();
        try {
            retryTemplate.execute(() -> {
                projectSyncWorker.sync(projectId);
                return null;
            });
        } catch (RetryException e) {
            var failure = e.getLastException();
            if (failure instanceof NotFoundException) {
                log.info("Project {} was deleted before its sync ran", projectId);
                return;
            }
            log.warn("Syncing project {} from its SDLC system failed: {}", projectId, describe(failure));
            // not requeued (it would fail again right away): kept in the dead-letter queue. Without the
            // failure as cause: logged above, and an error response's message includes the upstream body
            throw new AmqpRejectAndDontRequeueException("Sync of project " + projectId + " dead-lettered");
        } finally {
            projectSyncLock.lease(projectId, request.lockOwnerId()).release();
        }
    }

    /** The SDLC system failing or unreachable, or our own rate limit for it: worth trying again. */
    private static boolean mayPass(Throwable failure) {
        if (failure instanceof RestClientResponseException response) {
            return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 429;
        }
        return failure instanceof RestClientException || failure instanceof RateLimitExceededException;
    }

    /** The status only: an error response's message would include the upstream body. */
    private static String describe(Throwable failure) {
        return failure instanceof RestClientResponseException response
                ? "HTTP " + response.getStatusCode().value()
                : failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
}
