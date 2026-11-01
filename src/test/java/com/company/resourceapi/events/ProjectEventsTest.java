package com.company.resourceapi.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.events.ProjectUpdated.Field;
import com.company.resourceapi.exceptions.ConflictException;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.company.resourceapi.services.ProjectService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which events ProjectService publishes. */
@SpringBootTest
@RecordApplicationEvents
class ProjectEventsTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Autowired
    private ApplicationEvents events;

    private SdlcSystem sdlcSystem;
    private SdlcSystem otherSdlcSystem;

    @BeforeEach
    void setup() {
        sdlcSystem = sdlcSystemRepository.save(newSdlcSystem());
        otherSdlcSystem = sdlcSystemRepository.save(newSdlcSystem());
    }

    @Test
    void givenCreatedProjectThenProjectCreatedIsPublished() {
        var project = projectService.createProject(newProject("EVENTS-1", "Name"));

        assertThat(events.stream(ProjectCreated.class)).containsExactly(
                new ProjectCreated(project.getId(), sdlcSystem.getId(), "EVENTS-1", "Name"));
    }

    @Test
    void givenRenamedProjectThenProjectUpdatedNamesTheChange() throws Exception {
        var id = projectService.createProject(newProject("EVENTS-2", "Name")).getId();

        projectService.updateProject(id, project -> project.setName("New name"));

        assertThat(events.stream(ProjectUpdated.class)).containsExactly(
                new ProjectUpdated(id, sdlcSystem.getId(), "EVENTS-2", "New name", Set.of(Field.NAME)));
    }

    @Test
    void givenProjectMovedToAnotherSdlcProjectThenItIsRekeyed() throws Exception {
        var id = projectService.createProject(newProject("EVENTS-3", "Name")).getId();

        projectService.updateProject(id, project -> {
            project.setExternalId("EVENTS-3B");
            project.setSdlcSystem(otherSdlcSystem);
        });

        var event = events.stream(ProjectUpdated.class).findFirst().orElseThrow();
        assertThat(event.changed()).containsExactlyInAnyOrder(Field.EXTERNAL_ID, Field.SDLC_SYSTEM);
        assertThat(event.isRekeyed()).isTrue();
        assertThat(event.sdlcSystemId()).isEqualTo(otherSdlcSystem.getId());
    }

    @Test
    void givenUpdateThatChangesNothingThenNothingIsPublished() throws Exception {
        var id = projectService.createProject(newProject("EVENTS-4", "Name")).getId();

        projectService.updateProject(id, project -> project.setName("Name"));

        assertThat(events.stream(ProjectUpdated.class)).isEmpty();
    }

    @Test
    void givenRejectedUpdateThenNothingIsPublished() throws Exception {
        projectService.createProject(newProject("EVENTS-5", "Name"));
        var id = projectService.createProject(newProject("EVENTS-6", "Name")).getId();

        assertThatThrownBy(() -> projectService.updateProject(id, project -> project.setExternalId("EVENTS-5")))
                .isInstanceOf(ConflictException.class);

        assertThat(events.stream(ProjectUpdated.class)).isEmpty();
    }

    private Project newProject(String externalId, String name) {
        var project = new Project();
        project.setExternalId(externalId);
        project.setName(name);
        project.setSdlcSystem(sdlcSystem);
        return project;
    }

    private static SdlcSystem newSdlcSystem() {
        var system = new SdlcSystem();
        system.setBaseUrl("http://sdlc.example.com");
        return system;
    }
}
