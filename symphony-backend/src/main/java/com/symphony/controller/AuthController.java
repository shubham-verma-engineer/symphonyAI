package com.symphony.controller;

import com.symphony.config.DatabaseInitializer;
import com.symphony.config.JwtUtils;
import com.symphony.entity.User;
import com.symphony.entity.WorkspaceMember;
import com.symphony.repository.UserRepository;
import com.symphony.repository.WorkspaceMemberRepository;
import com.symphony.repository.WorkspaceRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserRepository userRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final JwtUtils jwtUtils;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        log.info("Simulating SSO authentication for username: '{}', role: '{}'", request.getUsername(), request.getRole());

        // Resolve User
        User user = userRepository.findByUsername(request.getUsername())
                .orElseGet(() -> userRepository.findById(DatabaseInitializer.DEFAULT_USER_ID)
                        .orElseThrow(() -> new RuntimeException("Default developer user not found.")));

        // Resolve Workspace
        UUID workspaceId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        String wsRole = "OWNER";
        List<WorkspaceMember> members = workspaceMemberRepository.findAllByUserId(user.getId());
        if (!members.isEmpty()) {
            workspaceId = members.get(0).getWorkspaceId();
            wsRole = members.get(0).getRole();
        }

        String token = jwtUtils.generateToken(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                request.getRole(),
                workspaceId,
                wsRole
        );

        String workspaceName = "Default Enterprise Workspace";
        com.symphony.entity.Workspace ws = workspaceRepository.findById(workspaceId).orElse(null);
        if (ws != null) {
            workspaceName = ws.getName();
        }

        return ResponseEntity.ok(LoginResponse.builder()
                .token(token)
                .username(user.getUsername())
                .role(request.getRole())
                .workspaceName(workspaceName)
                .build());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        private String username;
        private String role;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {
        private String token;
        private String username;
        private String role;
        private String workspaceName;
    }
}
