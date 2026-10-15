package com.company.resourceapi.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.resourceapi.entities.SdlcSystem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class SdlcSystemRepositoryTest {

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Test
    void givenNewSdlcSystemWhenSavedThenAuditDatesArePopulated() {
        // given
        var sdlcSystem = new SdlcSystem();
        sdlcSystem.setBaseUrl("http://youtrack.company.com");

        // when
        var saved = sdlcSystemRepository.saveAndFlush(sdlcSystem);

        // then
        assertThat(saved.getCreatedDate()).isNotNull();
        assertThat(saved.getLastModifiedDate()).isNotNull();
    }
}
