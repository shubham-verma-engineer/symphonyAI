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
        
        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        List<InnovationGap> gaps = gapRepository.findAllByWorkspaceIdOrderByCountFrequencyDesc(workspaceId);
        return ResponseEntity.ok(gaps);
    }

    @GetMapping("/telemetry")
    public ResponseEntity<List<Map<String, Object>>> getTelemetry(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        List<Map<String, Object>> stats = telemetryService.getStats(workspaceId);
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/gaps/trigger-cluster")
    public ResponseEntity<Void> triggerClustering(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        clusteringService.runClusteringPipeline(workspaceId);
        return ResponseEntity.ok().build();
    }
}
