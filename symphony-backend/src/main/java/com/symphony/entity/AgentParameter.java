package com.symphony.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "agent_parameters")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentParameter {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "param_name", nullable = false)
    private String paramName;

    @Column(name = "param_type", nullable = false)
    private String paramType; // 'string', 'number', 'boolean', 'array'

    @Column(name = "param_description")
    private String paramDescription;

    @Column(name = "is_required")
    private Boolean isRequired;
}
