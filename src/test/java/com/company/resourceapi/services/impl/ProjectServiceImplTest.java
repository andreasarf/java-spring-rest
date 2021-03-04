package com.company.resourceapi.services.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.repositories.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ProjectServiceImplTest {

    @Mock
    private ProjectRepository projectRepository;

    private ProjectServiceImpl projectService;

    @BeforeEach
    void setup() {
        projectService = new ProjectServiceImpl(projectRepository);
    }

    @Test
    void givenProjectWhenUpdateProjectThenSetModifiedLastDate() {
        // given
        Project updatedProject = mock(Project.class);
        Project resultantProject = mock(Project.class);
        when(projectRepository.save(updatedProject)).thenReturn(resultantProject);

        // when
        projectService.updateProject(updatedProject);

        // then
        verify(updatedProject).setLastModifiedDate(any());
        verify(projectRepository).save(updatedProject);
    }
}
