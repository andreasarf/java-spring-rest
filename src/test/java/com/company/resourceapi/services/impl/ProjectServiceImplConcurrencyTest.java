package com.company.resourceapi.services.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.resourceapi.entities.Project;
import com.company.resourceapi.repositories.ProjectRepository;
import com.company.resourceapi.repositories.SdlcSystemRepository;
import com.company.resourceapi.services.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ProjectServiceImplConcurrencyTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SdlcSystemRepository sdlcSystemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void givenConcurrentUpdatesOfDifferentFieldsThenBothChangesAreKept() throws Exception {
        // given
        var project = new Project();
        project.setExternalId("CONCURRENT");
        project.setName("Original");
        project.setSdlcSystem(sdlcSystemRepository.findById(3L).orElseThrow());
        long projectId = projectRepository.save(project).getId();

        var otherRequest = new TransactionTemplate(transactionManager);
        otherRequest.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        try {
            // when: another request renames the project after this update has loaded it
            projectService.updateProject(projectId, loaded -> {
                otherRequest.executeWithoutResult(status -> {
                    var concurrent = projectRepository.findById(projectId).orElseThrow();
                    concurrent.setName("Renamed concurrently");
                });
                loaded.setExternalId("CONCURRENT-EDITED");
            });

            // then
            var stored = projectRepository.findById(projectId).orElseThrow();
            assertThat(stored.getExternalId()).isEqualTo("CONCURRENT-EDITED");
            assertThat(stored.getName()).isEqualTo("Renamed concurrently");
        } finally {
            // the context (and its in-memory database) is shared with other test classes
            projectRepository.deleteById(projectId);
        }
    }
}
