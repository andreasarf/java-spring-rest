package com.company.resourceapi.services;

/**
 * A queued sync of a project.
 *
 * @param lockOwnerId owner of the project's sync lock, taken by the request and released by the
 *     consumer when the sync ends; null if the sync runs without the lock
 */
public record ProjectSyncRequest(long projectId, Long lockOwnerId) {
}
