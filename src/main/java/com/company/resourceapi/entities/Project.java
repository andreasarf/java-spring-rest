package com.company.resourceapi.entities;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "project")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@DynamicUpdate
public class Project {

    /** Length of the varchar columns in schema.sql. */
    public static final int MAX_TEXT_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @JsonProperty(access = READ_ONLY)
    private long id;

    @Column(name = "external_id", nullable = false)
    @NotBlank
    @Size(max = MAX_TEXT_LENGTH)
    private String externalId;

    @Column(name = "name")
    @Size(max = MAX_TEXT_LENGTH)
    private String name;

    @ManyToOne
    @JoinColumn(name = "sdlc_system_id")
    @NotNull
    private SdlcSystem sdlcSystem;

    @Column(name = "created_date", nullable = false)
    @CreatedDate
    @JsonProperty(access = READ_ONLY)
    private Instant createdDate;

    @Column(name = "last_modified_date", nullable = false)
    @LastModifiedDate
    @JsonProperty(access = READ_ONLY)
    private Instant lastModifiedDate;
}
