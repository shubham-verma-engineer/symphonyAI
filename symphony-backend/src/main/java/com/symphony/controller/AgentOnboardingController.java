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
        
        WorkspaceUserContext ctx = resolveWorkspaceAndUser(wsHeader, userHeader);
        Agent agent = agentService.onboardAgent(manifest, ctx.getWorkspaceId(), ctx.getUserId());
        return ResponseEntity.ok(agent);
    }

    @GetMapping
    public ResponseEntity<List<AgentSearchMatchDto>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        WorkspaceUserContext ctx = resolveWorkspaceAndUser(wsHeader, null);
        List<AgentSearchMatchDto> agents = agentService.listAll(ctx.getWorkspaceId());
        return ResponseEntity.ok(agents);
    }

    @PostMapping("/search")
    public ResponseEntity<List<AgentSearchMatchDto>> search(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader) {
        
        WorkspaceUserContext ctx = resolveWorkspaceAndUser(wsHeader, null);
        
        String query = (String) payload.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Integer limit = (Integer) payload.get("limit");
        if (limit == null) {
            limit = 5;
        }

        List<AgentSearchMatchDto> matches = agentService.search(ctx.getWorkspaceId(), query, limit);
        return ResponseEntity.ok(matches);
    }

    private WorkspaceUserContext resolveWorkspaceAndUser(String wsHeader, String userHeader) {
        UUID workspaceId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = DatabaseInitializer.DEFAULT_USER_ID;

        org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof com.symphony.config.SymphonyAuthentication symAuth) {
            workspaceId = symAuth.getWorkspaceId();
            userId = symAuth.getUserId();
        } else {
            if (wsHeader != null && !wsHeader.trim().isEmpty()) {
                workspaceId = UUID.fromString(wsHeader);
            }
            if (userHeader != null && !userHeader.trim().isEmpty()) {
                userId = UUID.fromString(userHeader);
            }
        }
        return new WorkspaceUserContext(workspaceId, userId);
    }

    @lombok.Value
    private static class WorkspaceUserContext {
        UUID workspaceId;
        UUID userId;
    }
}
