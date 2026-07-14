package com.symphony.config;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

public class SymphonyAuthentication extends AbstractAuthenticationToken {
    private final UUID userId;
    private final UUID workspaceId;
    private final String username;
    private final String role; // selected SSO role e.g. Admin, HR, Employee

    public SymphonyAuthentication(UUID userId, UUID workspaceId, String username, String role,
                                   Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.username = username;
        this.role = role;
        setAuthenticated(true);
    }

    public UUID getUserId() { return userId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getUsername() { return username; }
    public String getRole() { return role; }

    @Override
    public Object getCredentials() { return null; }

    @Override
    public Object getPrincipal() { return username; }
}
