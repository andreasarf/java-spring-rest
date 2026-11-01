package com.company.resourceapi.events;

import java.time.Instant;

/**
 * A project was created. Published inside the creating transaction; listeners see it after commit.
 *
 * @param occurredAt when the change was made; events about one project can reach a listener (or another
 *     system) out of order, so one older than the last applied is stale
 */
public record ProjectCreated(long projectId, long sdlcSystemId, String externalId, String name,
        Instant occurredAt) {
}
