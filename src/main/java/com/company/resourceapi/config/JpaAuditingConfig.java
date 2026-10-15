package com.company.resourceapi.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Populates the {@code @CreatedDate}/{@code @LastModifiedDate} fields of the entities.
 * Kept out of the application class so web slice tests don't need a JPA context.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /** The audit columns are {@code datetime}, so keep responses consistent with what is stored. */
    @Bean
    DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
