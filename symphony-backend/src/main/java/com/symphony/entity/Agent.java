package com.symphony.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "agents")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agent {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String description;

    @Transient
    private float[] descriptionEmbedding;

    @Column(name = "endpoint_url", nullable = false)
    private String endpointUrl;

    @Column(name = "health_check_url")
    private String healthCheckUrl;

    @Column(nullable = false)
    private String status; // 'PENDING', 'ACTIVE', 'UNHEALTHY', 'DEPRECATED'

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "registered_at", insertable = false, updatable = false)
    private OffsetDateTime registeredAt;

    @Column(name = "requires_rbac")
    private Boolean requiresRbac;

    @Column(name = "authorized_roles")
    private String authorizedRoles;

    @Column(name = "credential_vault_uri")
    private String credentialVaultUri;

    @Version
    @Column(name = "concurrency_version", nullable = false)
    private Integer concurrencyVersion;
}
