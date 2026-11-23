package com.symphony.controller;

import com.symphony.config.SymphonyAuthentication;
import com.symphony.dto.SupervisorResponseDto;
import com.symphony.service.PiiFirewallService;
import com.symphony.service.SupervisorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * Routes a conversational query through the PII firewall and the Supervisor.
     *
     * <p>Identity comes exclusively from the authenticated security context. An
     * earlier revision fell back to {@code X-Workspace-Id}, {@code X-User-Id},
     * and {@code X-User-Role} request headers whenever that context was absent.
     * {@code SecurityConfig} requires authentication on this path, so the branch
     * was unreachable — but it was a complete identity-spoofing bypass sitting
     * one configuration edit away from being live, and it contradicted
     * {@code docs/SECURITY-AND-ACCESS.md} section 2. It has been removed rather
     * than left to be rediscovered.
     */
    @PostMapping("/chat")
    public ResponseEntity<SupervisorResponseDto> chat(@RequestBody Map<String, String> payload) {

        String query = payload.get("query");
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof SymphonyAuthentication symAuth)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
        }

        UUID workspaceId = symAuth.getWorkspaceId();
        UUID userId = symAuth.getUserId();
        String userRole = symAuth.getRole();

        // 1. Audit and Sanitize user query through PII Firewall.
        //    The pre-mask query is deliberately not logged: application logs are
        //    shipped off-host, so logging it would defeat the masking for every
        //    log-based consumer. The masked text carries the placeholders, which
        //    is what an operator actually needs for traceability.
        PiiFirewallService.FirewallResult firewallRes = piiFirewall.sanitize(query);
        log.info("PII Firewall result: query sanitized={} (Masked: '{}')",
                firewallRes.isSanitized(), firewallRes.getMaskedText());

        // 2. Dispatch the masked query to the Supervisor Routing & Execution Engine
        SupervisorResponseDto response = supervisorService.routeAndExecute(
                workspaceId, userId, symAuth.getRoles(), userRole, firewallRes.getMaskedText()
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
