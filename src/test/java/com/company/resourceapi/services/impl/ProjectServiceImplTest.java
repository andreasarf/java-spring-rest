package com.company.resourceapi.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.entities.SdlcSystem;
import com.company.resourceapi.exceptions.ConflictException;
import com.company.resourceapi.repositories.ProjectRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ProjectServiceImplTest {

    private static final long PROJECT_ID = 1;

    @Mock
    private ProjectRepository projectRepository;

    private ProjectServiceImpl projectService;

    private Project storedProject;

    @BeforeEach
    void setup() {
        projectService = new ProjectServiceImpl(projectRepository,
                Validation.buildDefaultValidatorFactory().getValidator());

        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setId(1);
        storedProject = new Project();
        storedProject.setId(PROJECT_ID);
        storedProject.setExternalId("SAMPLEPROJECT");
        storedProject.setSdlcSystem(sdlcSystem);
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(storedProject));
    }

    @Test
    void givenNameOnlyUpdateWhenUpdateProjectThenSkipDuplicateCheck() throws Exception {
        // when
        Project result = projectService.updateProject(PROJECT_ID, project -> project.setName("Renamed"));

        // then
        assertThat(result).isSameAs(storedProject);
        assertThat(result.getName()).isEqualTo("Renamed");
        verify(projectRepository, never())
                .existsByExternalIdAndSdlcSystemIdAndIdNot(anyString(), anyLong(), anyLong());
    }

    @Test
    void givenInvalidUpdateWhenUpdateProjectThenThrowConstraintViolation() {
        assertThatThrownBy(() -> projectService.updateProject(PROJECT_ID, project -> project.setExternalId(" ")))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void givenDuplicateExternalIdWhenUpdateProjectThenThrowConflict() {
        // given
        when(projectRepository.existsByExternalIdAndSdlcSystemIdAndIdNot("PROJECTX", 1, PROJECT_ID))
                .thenReturn(true);

        // when / then
        assertThatThrownBy(() -> projectService.updateProject(PROJECT_ID, project -> project.setExternalId("PROJECTX")))
                .isInstanceOf(ConflictException.class);
    }
}
