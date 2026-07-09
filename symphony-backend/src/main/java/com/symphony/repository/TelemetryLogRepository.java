package com.symphony.repository;

import com.symphony.entity.TelemetryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public interface TelemetryLogRepository extends JpaRepository<TelemetryLog, UUID> {
    List<TelemetryLog> findAllByWorkspaceId(UUID workspaceId);

    @Query(value = "SELECT CAST(agent_id AS VARCHAR) AS agentId, COUNT(*) AS countRuns, " +
                   "AVG(execution_latency_ms) AS avgLatency, " +
                   "SUM(token_cost) AS totalCost, " +
                   "COUNT(CASE WHEN http_status_code >= 200 AND http_status_code < 300 THEN 1 END) * 100.0 / COUNT(*) AS successRate " +
                   "FROM telemetry_logs " +
                   "WHERE workspace_id = :workspaceId AND agent_id IS NOT NULL " +
                   "GROUP BY agent_id", nativeQuery = true)
    List<Map<String, Object>> getAgentAggregatedStats(@Param("workspaceId") UUID workspaceId);
}
