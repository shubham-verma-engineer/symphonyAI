package com.symphony.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.UUID;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String username;

    /**
     * BCrypt hash of the user's password. Never serialised: this entity is
     * reachable from repository queries used by controllers, and a stray
     * {@code ResponseEntity.ok(user)} would otherwise publish the hash.
     */
    @JsonIgnore
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(unique = true, nullable = false)
    private String email;

    /**
     * Platform scope — what the user may do inside the registry and telemetry
     * views. Distinct from {@link #corporateRoles}; see
     * {@code docs/SECURITY-AND-ACCESS.md} section 2.
     */
    @Column(name = "role_flag", nullable = false)
    private String roleFlag; // 'ADMIN', 'CREATOR', 'USER'

    /**
     * Agent access scope — the corporate (SSO) roles evaluated against
     * {@code agents.authorized_roles} when routing a payload. Comma-separated,
     * matching the convention already used by that column.
     */
    @Column(name = "corporate_roles")
    private String corporateRoles;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * {@link #corporateRoles} split into a list, with blanks discarded. Returns
     * an empty list when the user has no corporate roles assigned, which means
     * they can reach only agents that do not require RBAC.
     */
    @Transient
    @JsonIgnore
    public List<String> getCorporateRoleList() {
        if (corporateRoles == null || corporateRoles.isBlank()) {
            return List.of();
        }
        return Arrays.stream(corporateRoles.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .collect(Collectors.toList());
    }
}
