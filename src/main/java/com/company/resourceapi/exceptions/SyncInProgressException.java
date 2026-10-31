package com.company.resourceapi.exceptions;

public class SyncInProgressException extends RuntimeException {

    public SyncInProgressException(long projectId) {
        super("A sync of project %d is already running.".formatted(projectId));
    }
}
