package com.company.resourceapi.events;

import java.time.Instant;
import java.util.Set;

/**
 * A project was changed, through the API or by a sync from its SDLC system; carries the new state and
 * which fields changed. Not published when an update changes nothing.
 *
 * @param occurredAt when the change was made; events about one project can reach a listener (or another
 *     system) out of order, so one older than the last applied is stale
 */
public record ProjectUpdated(long projectId, long sdlcSystemId, String externalId, String name,
        Set<Field> changed, Instant occurredAt) {

    public enum Field {
        NAME, EXTERNAL_ID, SDLC_SYSTEM
    }

    public ProjectUpdated {
        changed = Set.copyOf(changed);
    }

    /** Whether the project now points at another project in an SDLC system. */
    public boolean isRekeyed() {
        return changed.contains(Field.EXTERNAL_ID) || changed.contains(Field.SDLC_SYSTEM);
    }
}
