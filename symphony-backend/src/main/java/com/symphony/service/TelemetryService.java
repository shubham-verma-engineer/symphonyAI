package com.symphony.service;

import com.symphony.entity.FailedQuery;
import com.symphony.entity.TelemetryLog;
import com.symphony.repository.FailedQueryRepository;
import com.symphony.repository.TelemetryLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryService {

    private final TelemetryLogRepository telemetryLogRepository;
    private final FailedQueryRepository failedQueryRepository;

    @Transactional
    public TelemetryLog logRouting(UUID workspaceId, UUID userId, UUID agentId, String query, String decision, int latencyMs, int statusCode, String userRole) {
        log.info("Logging telemetry: workspace='{}', agent='{}', decision='{}', status={}, role='{}'", workspaceId, agentId, decision, statusCode, userRole);
        
        BigDecimal cost = BigDecimal.valueOf(0.00015);
        if ("chained".equalsIgnoreCase(decision)) {
            cost = BigDecimal.valueOf(0.00045);
        } else if ("no_match".equalsIgnoreCase(decision)) {
            cost = BigDecimal.valueOf(0.00005);
        }

        TelemetryLog logEntity = TelemetryLog.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .agentId(agentId)
                .queryText(query.length() > 500 ? query.substring(0, 500) : query)
                .routingDecision(decision)
                .executionLatencyMs(latencyMs)
                .tokenCost(cost)
                .httpStatusCode(statusCode)
                .department(userRole != null ? userRole : "Employee")
                .build();
        
        return telemetryLogRepository.save(logEntity);
    }

    @Transactional
    public FailedQuery logFailedQuery(UUID workspaceId, String query) {
        log.info("Logging failed search query for gap detection: '{}'", query);
        
        FailedQuery failedQuery = FailedQuery.builder()
                .workspaceId(workspaceId)
                .rawQuery(query.length() > 500 ? query.substring(0, 500) : query)
                .isClustered(false)
                .build();
        
        return failedQueryRepository.save(failedQuery);
    }

    public List<Map<String, Object>> getStats(UUID workspaceId) {
        return telemetryLogRepository.getAgentAggregatedStats(workspaceId);
    }

    public List<Map<String, Object>> getDepartmentBillingStats(UUID workspaceId) {
        return telemetryLogRepository.getDepartmentBillingStats(workspaceId);
    }

    public List<TelemetryLog> getRawTelemetryLogs(UUID workspaceId) {
        return telemetryLogRepository.findAllByWorkspaceId(workspaceId);
    }
}
