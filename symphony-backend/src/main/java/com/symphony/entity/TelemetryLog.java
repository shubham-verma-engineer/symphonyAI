package com.symphony.entity;

import com.symphony.config.AesGcmEncryptor;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "telemetry_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelemetryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "query_text", nullable = false)
    @Convert(converter = AesGcmEncryptor.class)
    private String queryText;

    @Column(name = "routing_decision", nullable = false)
    private String routingDecision; // 'routed', 'chained', 'no_match'

    @Column(name = "execution_latency_ms")
    private Integer executionLatencyMs;

    @Column(name = "token_cost")
    private BigDecimal tokenCost;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Column(name = "executed_at", insertable = false, updatable = false)
    private OffsetDateTime executedAt;
}
