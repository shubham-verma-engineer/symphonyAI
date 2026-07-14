package com.symphony.controller;

import com.symphony.config.DatabaseInitializer;
import com.symphony.entity.InnovationGap;
import com.symphony.repository.InnovationGapRepository;
import com.symphony.service.GapClusteringService;
import com.symphony.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminAnalyticsController {

    private final InnovationGapRepository gapRepository;
    private final TelemetryService telemetryService;
    private final GapClusteringService clusteringService;

    @GetMapping("/gaps")
    public ResponseEntity<List<InnovationGap>> getGaps(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        UUID workspaceId = resolveWorkspace(wsHeader);
        List<InnovationGap> gaps = gapRepository.findAllByWorkspaceIdOrderByCountFrequencyDesc(workspaceId);
        return ResponseEntity.ok(gaps);
    }

    @GetMapping("/telemetry")
    public ResponseEntity<List<Map<String, Object>>> getTelemetry(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        UUID workspaceId = resolveWorkspace(wsHeader);
        List<Map<String, Object>> stats = telemetryService.getStats(workspaceId);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/billing/stats")
    public ResponseEntity<List<Map<String, Object>>> getBillingStats(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        UUID workspaceId = resolveWorkspace(wsHeader);
        List<Map<String, Object>> billingStats = telemetryService.getDepartmentBillingStats(workspaceId);
        return ResponseEntity.ok(billingStats);
    }

    @GetMapping("/billing/audit")
    public ResponseEntity<List<com.symphony.entity.TelemetryLog>> getBillingAudit(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        UUID workspaceId = resolveWorkspace(wsHeader);
        List<com.symphony.entity.TelemetryLog> logs = telemetryService.getRawTelemetryLogs(workspaceId);
        return ResponseEntity.ok(logs);
    }

    @PostMapping("/gaps/trigger-cluster")
    public ResponseEntity<Void> triggerClustering(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        UUID workspaceId = resolveWorkspace(wsHeader);
        clusteringService.runClusteringPipeline(workspaceId);
        return ResponseEntity.ok().build();
    }

    private UUID resolveWorkspace(String wsHeader) {
        UUID workspaceId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof com.symphony.config.SymphonyAuthentication symAuth) {
            workspaceId = symAuth.getWorkspaceId();
        } else {
            if (wsHeader != null && !wsHeader.trim().isEmpty()) {
                workspaceId = UUID.fromString(wsHeader);
            }
        }
        return workspaceId;
    }
}
