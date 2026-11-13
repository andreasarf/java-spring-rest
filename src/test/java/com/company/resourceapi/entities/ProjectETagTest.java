package com.company.resourceapi.entities;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectETagTest {

    @Test
    void givenNoNameAndTheNameNullThenETagsDiffer() {
        assertThat(ProjectETag.of(project("KEY", null))).isNotEqualTo(ProjectETag.of(project("KEY", "null")));
    }

    @Test
    void givenSameTextSplitDifferentlyBetweenFieldsThenETagsDiffer() {
        assertThat(ProjectETag.of(project("A\nB", "C"))).isNotEqualTo(ProjectETag.of(project("A", "B\nC")));
    }

    @Test
    void givenSameContentThenSameETag() {
        assertThat(ProjectETag.of(project("KEY", "Name"))).isEqualTo(ProjectETag.of(project("KEY", "Name")));
    }

    private static Project project(String externalId, String name) {
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setId(1);
        var project = new Project();
        project.setId(7);
        project.setExternalId(externalId);
        project.setName(name);
        project.setSdlcSystem(sdlcSystem);
        return project;
    }
}
