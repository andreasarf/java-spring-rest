package com.company.resourceapi.events;

/** A project was created. Published inside the creating transaction; listeners see it after commit. */
public record ProjectCreated(long projectId, long sdlcSystemId, String externalId, String name) {
}
