package com.symphony.entity;

import com.symphony.config.AesGcmEncryptor;
import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "failed_queries")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FailedQuery {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "raw_query", nullable = false)
    @Convert(converter = AesGcmEncryptor.class)
    private String rawQuery;

    @Column(name = "is_clustered")
    private Boolean isClustered;

    @Column(name = "logged_at", insertable = false, updatable = false)
    private OffsetDateTime loggedAt;
}
