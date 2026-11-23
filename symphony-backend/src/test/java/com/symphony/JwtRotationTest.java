package com.symphony;

import com.symphony.config.JwtUtils;
import com.symphony.config.jwt.InMemoryJwtKeyStore;
import com.symphony.config.jwt.JwtKeyProvider;
import com.symphony.config.jwt.JwtKeyRing;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 of plan 0007: signing keys, {@code kid} selection, and the rotation
 * overlap window.
 *
 * <p>B2 was that {@code symphony.jwt.secret} shipped a working HS256 key in
 * {@code application.yml}. HS256 verifies with the same key it signs with, so
 * anyone holding the repository could mint a token for any user, role and
 * workspace, and {@code docs/SECRETS-MANAGEMENT.md} section 4's mandated 90-day
 * rotation with 24-hour drainage had no mechanism behind it.
 *
 * <p>Uses a mutable clock rather than real time, so the overlap boundary is
 * tested at the exact instant it matters instead of approximately.
 */
public class JwtRotationTest {

    /** A clock whose instant the test moves, so time-dependent rules are exact. */
    private static class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private static final Duration ROTATION_PERIOD = Duration.ofDays(90);
    private static final Duration OVERLAP = Duration.ofHours(24);

    private JwtKeyProvider provider(MovableClock clock) {
        return new JwtKeyProvider(new InMemoryJwtKeyStore(), ROTATION_PERIOD, OVERLAP, clock);
    }

    @Test
    @DisplayName("a fresh provider generates an RSA signing key with a kid")
    void generatesSigningKeyWithKid() {
        JwtKeyRing.Key key = provider(new MovableClock()).signingKey();

        assertNotNull(key.privateKey());
        assertNotNull(key.publicKey());
        assertEquals("RSA", key.privateKey().getAlgorithm());
        assertNotNull(key.kid());
        assertFalse(key.kid().isBlank());
    }

    @Test
    @DisplayName("the active key verifies its own tokens")
    void activeKeyVerifiesItsOwnTokens() {
        MovableClock clock = new MovableClock();
        JwtKeyProvider keyProvider = provider(clock);
        JwtKeyRing.Key active = keyProvider.signingKey();

        assertTrue(keyProvider.verificationKey(active.kid()).isPresent());
    }

    @Test
    @DisplayName("rotation produces a new key and a new kid")
    void rotationProducesANewKey() {
        MovableClock clock = new MovableClock();
        JwtKeyProvider keyProvider = provider(clock);

        String originalKid = keyProvider.signingKey().kid();
        clock.advance(ROTATION_PERIOD);
        String rotatedKid = keyProvider.rotate().kid();

        assertNotEquals(originalKid, rotatedKid);
        assertEquals(rotatedKid, keyProvider.signingKey().kid(),
                "New tokens must be signed with the newly rotated key");
    }

    @Test
    @DisplayName("a rotated-out key still verifies inside the overlap window")
    void retiredKeyStillVerifiesInsideTheOverlapWindow() {
        MovableClock clock = new MovableClock();
        JwtKeyProvider keyProvider = provider(clock);
        String oldKid = keyProvider.signingKey().kid();

        clock.advance(ROTATION_PERIOD);
        keyProvider.rotate();

        // Immediately after rotation, and just before the window closes.
        assertTrue(keyProvider.verificationKey(oldKid).isPresent(),
                "Tokens already in circulation must survive the rotation itself");

        clock.advance(OVERLAP.minusMinutes(1));
        assertTrue(keyProvider.verificationKey(oldKid).isPresent(),
                "The retired key must still verify one minute before the window closes");
    }

    @Test
    @DisplayName("a rotated-out key is refused once the overlap window closes")
    void retiredKeyIsRefusedAfterTheOverlapWindow() {
        MovableClock clock = new MovableClock();
        JwtKeyProvider keyProvider = provider(clock);
        String oldKid = keyProvider.signingKey().kid();

        clock.advance(ROTATION_PERIOD);
        keyProvider.rotate();
        clock.advance(OVERLAP.plusMinutes(1));

        assertTrue(keyProvider.verificationKey(oldKid).isEmpty(),
                "Past the overlap window the retired key must no longer verify");
    }

    @Test
    @DisplayName("an unknown kid is refused rather than falling back to a default key")
    void unknownKidIsRefused() {
        JwtKeyProvider keyProvider = provider(new MovableClock());

        assertEquals(Optional.empty(), keyProvider.verificationKey("not-a-real-kid"));
        assertEquals(Optional.empty(), keyProvider.verificationKey(null));
    }

    @Test
    @DisplayName("a token signed with the old committed HS256 secret is rejected")
    void legacyHs256TokenIsRejected() {
        // The exact value that used to sit in application.yml. Asserted through
        // JwtUtils rather than by inspecting the header, because what matters is
        // that the verification path refuses it.
        String committedSecret =
                "dGhpcy1pcy1hLXNlY3JldC1rZXktZm9yLXN5bXBob255LWFpLWp3dC10b2tlbi1nZW5lcmF0aW9uLXdpdGgtMzItYnl0ZXM=";
        String legacyToken = Jwts.builder()
                .issuer("symphony-ai.internal")
                .subject("00000000-0000-0000-0000-000000000002")
                .claim("roles", java.util.List.of("Admin"))
                .claim("platform_role", "ADMIN")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 86_400_000L))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(committedSecret)))
                .compact();

        JwtUtils jwtUtils = new JwtUtils(provider(new MovableClock()), 86_400_000L);

        assertFalse(jwtUtils.validateToken(legacyToken),
                "A token signed with the previously committed HS256 secret must not validate");
        assertThrows(Exception.class, () -> jwtUtils.parseToken(legacyToken),
                "Parsing a legacy HS256 token must fail rather than yield claims");
    }

    @Test
    @DisplayName("a token this service issued validates, and one signed by a foreign RSA key does not")
    void onlyTokensSignedByAKnownKeyValidate() {
        JwtKeyProvider keyProvider = provider(new MovableClock());
        JwtUtils jwtUtils = new JwtUtils(keyProvider, 86_400_000L);

        String ourToken = jwtUtils.generateToken(
                java.util.UUID.randomUUID(), "jane", "jane@enterprise.com",
                java.util.List.of("HR"), "USER", java.util.UUID.randomUUID(), "VIEWER");
        assertTrue(jwtUtils.validateToken(ourToken));
        assertEquals(java.util.List.of("HR"), jwtUtils.parseToken(ourToken).get("roles", java.util.List.class));

        // A different provider means a different key with a different kid: the
        // signature is valid RSA, but not by a key this instance trusts.
        JwtUtils foreign = new JwtUtils(provider(new MovableClock()), 86_400_000L);
        String foreignToken = foreign.generateToken(
                java.util.UUID.randomUUID(), "attacker", "attacker@elsewhere.com",
                java.util.List.of("Admin"), "ADMIN", java.util.UUID.randomUUID(), "OWNER");

        assertFalse(jwtUtils.validateToken(foreignToken),
                "A token signed by an unknown RSA key must not validate");
    }

    @Test
    @DisplayName("the retired set does not grow without bound across rotations")
    void retiredKeysArePrunedOnceTheirWindowCloses() {
        MovableClock clock = new MovableClock();
        JwtKeyProvider keyProvider = provider(clock);

        for (int i = 0; i < 5; i++) {
            clock.advance(ROTATION_PERIOD);
            keyProvider.rotate();
        }

        // Each rotation is a full period apart, far beyond the overlap, so only
        // the key retired by the most recent rotation should remain.
        assertEquals(1, keyProvider.currentKeyRing().retired().size(),
                "Keys past their overlap window must be dropped, not accumulated");
    }

    @Test
    @DisplayName("a retired key cannot sign")
    void retiredKeyCannotSign() {
        MovableClock clock = new MovableClock();
        JwtKeyProvider keyProvider = provider(clock);

        clock.advance(ROTATION_PERIOD);
        keyProvider.rotate();

        JwtKeyRing.Key retired = keyProvider.currentKeyRing().retired().get(0);
        assertNull(retired.privateKey(),
                "A retired key must be verify-only; keeping its private key would let it keep signing");
    }

}
