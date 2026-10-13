package com.symphony.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.*;

@Component
public class JwtUtils {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtils(
            @Value("${symphony.jwt.secret}") String secret,
            @Value("${symphony.jwt.expiration-ms}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    /**
     * Mints a session token for an already-authenticated user.
     *
     * <p>Both role layers are carried, and both are read from the database by
     * the caller — never from the login request. {@code roles} holds the
     * corporate (SSO) roles that agent RBAC is evaluated against;
     * {@code platform_role} holds the registry-scope role from
     * {@code users.role_flag}. See {@code docs/SECURITY-AND-ACCESS.md} section 2.
     */
    public String generateToken(UUID userId, String username, String email, List<String> corporateRoles,
                                String platformRole, UUID workspaceId, String workspaceRole) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("name", username);
        claims.put("email", email);
        claims.put("roles", corporateRoles == null ? List.of() : List.copyOf(corporateRoles));
        claims.put("platform_role", platformRole);

        Map<String, Object> ws = new HashMap<>();
        ws.put("workspace_id", workspaceId.toString());
        ws.put("membership_role", workspaceRole);
        claims.put("assigned_workspaces", List.of(ws));

        return Jwts.builder()
                .issuer("symphony-ai.internal")
                .subject(userId.toString())
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
