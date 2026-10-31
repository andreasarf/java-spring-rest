package com.company.resourceapi.web;

import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import com.company.resourceapi.exceptions.NotFoundException;
import com.company.resourceapi.exceptions.SyncInProgressException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
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

    /** Another request already started a sync of this project, which is still running. */
    @ExceptionHandler(SyncInProgressException.class)
    ResponseEntity<Void> handleSyncInProgress(SyncInProgressException e) {
        log.debug(e.getMessage());
        return ResponseEntity.status(CONFLICT).build();
    }

    /** The background task executor is at its concurrency limit; the client can retry shortly. */
    @ExceptionHandler(TaskRejectedException.class)
    ResponseEntity<Void> handleTaskRejected(TaskRejectedException e) {
        log.warn("Background task rejected: {}", e.getMessage());
        return ResponseEntity.status(SERVICE_UNAVAILABLE).header(RETRY_AFTER, "1").build();
    }
}
