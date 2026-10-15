package com.company.resourceapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Populates the {@code @CreatedDate}/{@code @LastModifiedDate} fields of the entities.
 * Kept out of the application class so web slice tests don't need a JPA context.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
