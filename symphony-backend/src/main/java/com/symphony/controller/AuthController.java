package com.symphony.controller;

import com.symphony.config.JwtUtils;
import com.symphony.entity.User;
import com.symphony.entity.WorkspaceMember;
import com.symphony.repository.UserRepository;
import com.symphony.repository.WorkspaceMemberRepository;
import com.symphony.repository.WorkspaceRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
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
    private final PasswordEncoder passwordEncoder;

    /**
     * Authenticates a user and issues a session JWT.
     *
     * <p>Every identity attribute in the token is read from the database. The
     * request body supplies only a username and a password; it cannot influence
     * the roles, the workspace, or which user is resolved. An earlier revision
     * accepted a {@code role} field and copied it straight into the token's
     * {@code roles} claim — the claim that all downstream RBAC decisions trust —
     * while an unrecognised username fell back to the seeded owner account. That
     * combination handed out owner-level tokens to unauthenticated callers.
     *
     * <p>Both failure modes now return 401 with the same message, so the
     * response does not reveal whether a given username exists.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        String username = request.getUsername();
        log.info("Authentication attempt for username '{}'", username);

        if (username == null || username.isBlank() || request.getPassword() == null || request.getPassword().isEmpty()) {
            throw unauthorized();
        }

        User user = userRepository.findByUsername(username).orElse(null);

        // Compare against a hash even when the user does not exist, so that a
        // missing account and a wrong password take comparable time and cannot
        // be distinguished by response latency.
        String storedHash = user != null ? user.getPasswordHash() : NON_EXISTENT_USER_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), storedHash);

        if (user == null || !passwordMatches) {
            log.warn("Authentication failed for username '{}'", username);
            throw unauthorized();
        }

        // Workspace membership comes from the user's actual rows. A user with no
        // membership is authenticated but has no workspace to act in, which is a
        // provisioning error rather than a credential error.
        List<WorkspaceMember> memberships = workspaceMemberRepository.findAllByUserId(user.getId());
        if (memberships.isEmpty()) {
            log.error("User '{}' authenticated but belongs to no workspace", username);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your account is not assigned to a workspace. Contact your platform administrator.");
        }
        WorkspaceMember membership = memberships.get(0);
        UUID workspaceId = membership.getWorkspaceId();

        List<String> corporateRoles = user.getCorporateRoleList();
        String token = jwtUtils.generateToken(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                corporateRoles,
                user.getRoleFlag(),
                workspaceId,
                membership.getRole()
        );

        String workspaceName = Optional.ofNullable(workspaceRepository.findById(workspaceId).orElse(null))
                .map(com.symphony.entity.Workspace::getName)
                .orElse("Unknown Workspace");

        log.info("Authenticated '{}' with platform role '{}' in workspace '{}'",
                username, user.getRoleFlag(), workspaceName);

        return ResponseEntity.ok(LoginResponse.builder()
                .token(token)
                .username(user.getUsername())
                .roles(corporateRoles)
                .platformRole(user.getRoleFlag())
                .workspaceRole(membership.getRole())
                .workspaceName(workspaceName)
                .build());
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password.");
    }

    /**
     * A valid BCrypt hash of a value no user can supply, used as the comparison
     * target when the username does not exist. Its only job is to make the
     * encoder do real work on that path.
     */
    private static final String NON_EXISTENT_USER_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginResponse {
        private String token;
        private String username;
        /** Corporate (SSO) roles, as resolved from the database. */
        private List<String> roles;
        /** Registry-scope role from {@code users.role_flag}. */
        private String platformRole;
        /** Membership role within the resolved workspace. */
        private String workspaceRole;
        private String workspaceName;
    }
}
