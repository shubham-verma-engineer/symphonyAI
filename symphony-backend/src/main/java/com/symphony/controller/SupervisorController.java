package com.symphony.controller;

import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.SupervisorResponseDto;
import com.symphony.service.SupervisorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/supervisor")
@RequiredArgsConstructor
@Slf4j
public class SupervisorController {

    private final SupervisorService supervisorService;

    @PostMapping("/chat")
    public ResponseEntity<SupervisorResponseDto> chat(
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader) {
        
        String query = payload.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = userHeader != null ? UUID.fromString(userHeader) : DatabaseInitializer.DEFAULT_USER_ID;

        SupervisorResponseDto response = supervisorService.routeAndExecute(workspaceId, userId, query);
        return ResponseEntity.ok(response);
    }
}
