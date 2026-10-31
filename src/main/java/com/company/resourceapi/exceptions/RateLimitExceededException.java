package com.company.resourceapi.exceptions;

import lombok.Getter;

/** One of our own rate limits refused the request; it can be retried after {@link #getRetryAfterSeconds()}. */
@Getter
public class RateLimitExceededException extends RuntimeException {

    /** Whose limit: the caller's own requests (429) or the application's calls to a system (503). */
    public enum Scope { CALLER, UPSTREAM }

    private final Scope scope;
    private final long retryAfterSeconds;

    public RateLimitExceededException(Scope scope, String message, long retryAfterSeconds) {
        super(message);
        this.scope = scope;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
