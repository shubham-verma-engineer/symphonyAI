package com.symphony.entity;

import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "workspace_members")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(WorkspaceMember.WorkspaceMemberId.class)
public class WorkspaceMember {

    @Id
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private String role; // 'OWNER', 'EDITOR', 'VIEWER'

    @Column(name = "joined_at", insertable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkspaceMemberId implements Serializable {
        private UUID workspaceId;
        private UUID userId;
    }
}
