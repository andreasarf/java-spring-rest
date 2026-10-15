package com.company.resourceapi.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void givenChangedProjectWhenSavedThenLastModifiedDateIsUpdated() {
        // given
        var project = projectRepository.findById(2L).orElseThrow();
        project.setLastModifiedDate(Instant.EPOCH);
        project.setName("Renamed");

        // when
        var saved = projectRepository.saveAndFlush(project);

        // then
        assertThat(saved.getLastModifiedDate()).isAfter(Instant.EPOCH);
    }
}
