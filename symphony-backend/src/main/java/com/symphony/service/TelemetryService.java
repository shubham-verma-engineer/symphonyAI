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
    public TelemetryLog logRouting(UUID workspaceId, UUID userId, UUID agentId, String query, String decision, int latencyMs, int statusCode) {
        log.info("Logging telemetry: workspace='{}', agent='{}', decision='{}', status={}", workspaceId, agentId, decision, statusCode);
        
        TelemetryLog logEntity = TelemetryLog.builder()
                .workspaceId(workspaceId)
                .userId(userId)
                .agentId(agentId)
                .queryText(query.length() > 500 ? query.substring(0, 500) : query)
                .routingDecision(decision)
                .executionLatencyMs(latencyMs)
                .tokenCost(BigDecimal.valueOf(0.0001)) // Mock trace token cost per LLM routing check
                .httpStatusCode(statusCode)
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
}
