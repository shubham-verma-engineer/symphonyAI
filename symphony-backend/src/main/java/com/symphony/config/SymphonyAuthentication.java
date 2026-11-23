package com.symphony.config;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class SymphonyAuthentication extends AbstractAuthenticationToken {
    private final UUID userId;
    private final UUID workspaceId;
    private final String username;

    /**
     * Corporate (SSO) roles from the token's {@code roles} claim, evaluated
     * against {@code agents.authorized_roles} when routing. A user may hold
     * several, and holding any one of an agent's authorised roles grants
     * access — so the whole list matters, not just the first entry.
     */
    private final List<String> roles;

    /** Registry-scope role from the token's {@code platform_role} claim. */
    private final String platformRole;

    public SymphonyAuthentication(UUID userId, UUID workspaceId, String username, List<String> roles,
                                  String platformRole, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.username = username;
        this.roles = roles == null ? List.of() : List.copyOf(roles);
        this.platformRole = platformRole;
        setAuthenticated(true);
    }

    public UUID getUserId() { return userId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getUsername() { return username; }
    public List<String> getRoles() { return roles; }
    public String getPlatformRole() { return platformRole; }

    /**
     * The first corporate role, used only for display and telemetry labelling.
     * Authorisation decisions must use {@link #getRoles()} so that a user's
     * other roles are not silently ignored.
     */
    public String getRole() {
        return roles.isEmpty() ? "Employee" : roles.get(0);
    }

    @Override
    public Object getCredentials() { return null; }

    @Override
    public Object getPrincipal() { return username; }
}
