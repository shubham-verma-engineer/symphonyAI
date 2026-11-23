package com.symphony.config;

import com.symphony.config.jwt.JwtKeyProvider;
import com.symphony.config.jwt.JwtKeyRing;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JweHeader;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.*;

/**
 * Mints and validates session tokens.
 *
 * <p>Signs with RS256 using the active key from {@link JwtKeyProvider} and
 * stamps that key's {@code kid} into the header. Verification resolves the key
 * from the {@code kid}, which is what allows a rotated-out key to keep
 * verifying for an overlap window instead of invalidating every token the
 * moment a key changes.
 *
 * <p>This replaced a single HMAC key read from {@code application.yml}. Because
 * HS256 verifies with the same key it signs with, that committed value let
 * anyone holding the repository mint a token for any user, role and workspace.
 * Tokens signed that way carry no {@code kid} and are now rejected.
 */
@Component
@Slf4j
public class JwtUtils {

    private final JwtKeyProvider keyProvider;
    private final long expirationMs;

    public JwtUtils(JwtKeyProvider keyProvider,
                    @org.springframework.beans.factory.annotation.Value("${symphony.jwt.expiration-ms}")
                    long expirationMs) {
        this.keyProvider = keyProvider;
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

        JwtKeyRing.Key signingKey = keyProvider.signingKey();

        return Jwts.builder()
                .header().keyId(signingKey.kid()).and()
                .issuer("symphony-ai.internal")
                .subject(userId.toString())
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(signingKey.privateKey(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Parses and verifies a token, throwing if it is not currently acceptable.
     *
     * <p>The key is chosen from the header's {@code kid} rather than tried
     * against every known key, so a token can only be verified by the key that
     * actually signed it.
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .keyLocator(new KidKeyLocator())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            // Logged at debug: an invalid token is an ordinary event on a public
            // endpoint, and logging it louder invites an attacker to fill the log.
            log.debug("Rejected token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Resolves the verification key from the token header's {@code kid}.
     *
     * <p>A missing or unrecognised {@code kid} is refused rather than falling
     * back to a default key. That refusal is what rejects tokens signed with the
     * old committed HS256 secret, which had no {@code kid} at all.
     */
    private class KidKeyLocator extends LocatorAdapter<Key> {
        @Override
        protected Key locate(JwsHeader header) {
            String kid = header.getKeyId();
            if (kid == null || kid.isBlank()) {
                throw new io.jsonwebtoken.JwtException(
                        "Token header carries no kid; it was not issued by this service.");
            }
            return keyProvider.verificationKey(kid)
                    .map(key -> (Key) key.publicKey())
                    .orElseThrow(() -> new io.jsonwebtoken.JwtException(
                            "Token was signed with key '" + kid + "', which is unknown or no longer accepted."));
        }

        @Override
        protected Key locate(JweHeader header) {
            throw new io.jsonwebtoken.JwtException("Encrypted tokens are not issued by this service.");
        }
    }
}
