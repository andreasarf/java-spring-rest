package com.company.resourceapi.clients;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A project as described by its SDLC system (Jira-style project resource); other fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalProject(String key, String name, String description) {
}
