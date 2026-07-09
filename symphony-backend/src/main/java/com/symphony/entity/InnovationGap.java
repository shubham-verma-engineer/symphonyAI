package com.symphony.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "innovation_gaps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InnovationGap {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String title;

    private String summary;

    @Column(name = "count_frequency")
    private Integer countFrequency;

    @Column(name = "detected_at", insertable = false, updatable = false)
    private OffsetDateTime detectedAt;
}
