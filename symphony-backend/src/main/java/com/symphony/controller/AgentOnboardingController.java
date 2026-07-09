package com.symphony.controller;

import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.AgentSearchMatchDto;
import com.symphony.entity.Agent;
import com.symphony.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@Slf4j
public class AgentOnboardingController {

    private final AgentService agentService;

    @PostMapping("/onboard")
    public ResponseEntity<Agent> onboard(
            @Valid @RequestBody AgentManifestDto manifest,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        
        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = userHeader != null ? UUID.fromString(userHeader) : DatabaseInitializer.DEFAULT_USER_ID;

        Agent agent = agentService.onboardAgent(manifest, workspaceId, userId);
        return ResponseEntity.ok(agent);
    }

    @GetMapping
    public ResponseEntity<List<AgentSearchMatchDto>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        List<AgentSearchMatchDto> agents = agentService.listAll(workspaceId);
        return ResponseEntity.ok(agents);
    }

    @PostMapping("/search")
    public ResponseEntity<List<AgentSearchMatchDto>> search(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        
        String query = (String) payload.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Integer limit = (Integer) payload.get("limit");
        if (limit == null) {
            limit = 5;
        }

        List<AgentSearchMatchDto> matches = agentService.search(workspaceId, query, limit);
        return ResponseEntity.ok(matches);
    }
}
