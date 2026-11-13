package com.company.resourceapi.exceptions;

/** The project changed since the client read it ({@code If-Match} doesn't match its current ETag). */
public class PreconditionFailedException extends RuntimeException {

    public PreconditionFailedException(long projectId) {
        super("Project %d was changed since it was read.".formatted(projectId));
    }
}
