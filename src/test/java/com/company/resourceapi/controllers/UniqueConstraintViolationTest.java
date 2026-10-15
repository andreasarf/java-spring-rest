package com.company.resourceapi.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class UniqueConstraintViolationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Test
    void givenDuplicateProjectWhenSavedThenRecognisedAsUniqueConstraintViolation() {
        // given: (SAMPLEPROJECT, 1) already exists in data.sql
        var duplicate = new Project();
        duplicate.setExternalId("SAMPLEPROJECT");
        duplicate.setSdlcSystem(sdlcSystemRepository.findById(1L).orElseThrow());

        // when
        var e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> projectRepository.saveAndFlush(duplicate));

        // then
        assertThat(ProjectRestController.isUniqueConstraintViolation(e)).isTrue();
    }

    @Test
    void givenOtherIntegrityViolationThenNotRecognisedAsUniqueConstraintViolation() {
        var e = new DataIntegrityViolationException("value too long",
                new DataException("value too long", new SQLException()));

        assertThat(ProjectRestController.isUniqueConstraintViolation(e)).isFalse();
    }

    @Test
    void givenWrappedUniqueViolationThenRecognisedAsUniqueConstraintViolation() {
        // e.g. a commit-time failure wrapped by the persistence provider
        var e = new DataIntegrityViolationException("could not commit",
                new PersistenceException(new ConstraintViolationException(
                        "duplicate", new SQLException(), ConstraintKind.UNIQUE, "system_project")));

        assertThat(ProjectRestController.isUniqueConstraintViolation(e)).isTrue();
    }
}
