package com.symphony.controller;

import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.SupervisorResponseDto;
import com.symphony.service.PiiFirewallService;
import com.symphony.service.SupervisorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/supervisor")
@RequiredArgsConstructor
@Slf4j
public class SupervisorController {

    private final SupervisorService supervisorService;
    private final PiiFirewallService piiFirewall;

    @PostMapping("/chat")
    public ResponseEntity<SupervisorResponseDto> chat(
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestHeader(value = "X-User-Role", required = false) String roleHeader) {
        
        String query = payload.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        UUID workspaceId = wsHeader != null ? UUID.fromString(wsHeader) : DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = userHeader != null ? UUID.fromString(userHeader) : DatabaseInitializer.DEFAULT_USER_ID;
        String userRole = roleHeader != null ? roleHeader : "Employee";

        // 1. Audit and Sanitize user query through PII Firewall
        PiiFirewallService.FirewallResult firewallRes = piiFirewall.sanitize(query);
        log.info("PII Firewall result: query sanitized={} (Original: '{}', Masked: '{}')", 
                firewallRes.isSanitized(), query, firewallRes.getMaskedText());

        // 2. Dispatch the masked query to the Supervisor Routing & Execution Engine
        SupervisorResponseDto response = supervisorService.routeAndExecute(
                workspaceId, userId, userRole, firewallRes.getMaskedText()
        );

        // 3. Detokenize final outputs (unmask the placeholders)
        String unmaskedText = piiFirewall.detokenize(response.getTextResponse(), firewallRes.getTokenMap());
        response.setTextResponse(unmaskedText);

        // 4. Populate PII metadata properties
        response.setPiiSanitized(firewallRes.isSanitized());
        
        List<String> sanitizedFields = new ArrayList<>();
        if (firewallRes.isSanitized()) {
            for (String key : firewallRes.getTokenMap().keySet()) {
                if (key.contains("EMAIL")) sanitizedFields.add("Email Address");
                if (key.contains("CARD")) sanitizedFields.add("Credit Card");
                if (key.contains("SSN")) sanitizedFields.add("Social Security Number");
            }
        }
        response.setSanitizedFields(sanitizedFields);

        return ResponseEntity.ok(response);
    }
}
