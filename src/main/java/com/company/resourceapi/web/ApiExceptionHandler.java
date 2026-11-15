package com.company.resourceapi.web;

import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.PRECONDITION_FAILED;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.exceptions.PreconditionFailedException;
import com.company.resourceapi.exceptions.RateLimitExceededException;
import com.company.resourceapi.exceptions.SyncInProgressException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Error responses shared by all controllers. */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Void> handleNotFound(NotFoundException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(NOT_FOUND).build();
    }

    /** The client's copy is out of date: it should read the project again and reapply its change. */
    @ExceptionHandler(PreconditionFailedException.class)
    ResponseEntity<Void> handlePreconditionFailed(PreconditionFailedException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(PRECONDITION_FAILED).build();
    }

    /**
     * Another update held the project's row longer than the database's lock timeout (a conditional
     * update waits for it): busy, not wrong, so the client can retry shortly.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<Void> handleLockTimeout(PessimisticLockingFailureException e) {
        log.info("Project busy, update not applied: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(SERVICE_UNAVAILABLE).header(RETRY_AFTER, "1").build();
    }

    /** Another request already started a sync of this project, which is still running. */
    @ExceptionHandler(SyncInProgressException.class)
    ResponseEntity<Void> handleSyncInProgress(SyncInProgressException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(CONFLICT).build();
    }

    /**
     * One of our rate limits: the caller's own requests are 429; our calls to an SDLC system being
     * limited is a temporary unavailability on our side, 503.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<Void> handleRateLimitExceeded(RateLimitExceededException e) {
        var caller = e.getScope() == RateLimitExceededException.Scope.CALLER;
        // a refused caller is expected, possibly many times a second; holding back our own calls is worth seeing
        if (caller) {
            log.debug(e.getMessage());
        } else {
            log.info(e.getMessage());
        }
        var status = caller ? TOO_MANY_REQUESTS : SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).header(RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds())).build();
    }

    /** RabbitMQ is unreachable or refused a message the request needed to queue (e.g. a sync). */
    @ExceptionHandler(AmqpException.class)
    ResponseEntity<Void> handleMessagingFailure(AmqpException e) {
        log.warn("Message broker unavailable: {}", e.getMessage());
        return ResponseEntity.status(SERVICE_UNAVAILABLE).header(RETRY_AFTER, "5").build();
    }

    /** The background task executor is at its concurrency limit; the client can retry shortly. */
    @ExceptionHandler(TaskRejectedException.class)
    ResponseEntity<Void> handleTaskRejected(TaskRejectedException e) {
        log.warn("Background task rejected: {}", e.getMessage());
        return ResponseEntity.status(SERVICE_UNAVAILABLE).header(RETRY_AFTER, "1").build();
    }
}
