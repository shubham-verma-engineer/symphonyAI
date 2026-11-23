package com.symphony.config.jwt;

import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the signing and verification keys for session tokens.
 *
 * <p>Replaces the single symmetric HMAC key that was committed to
 * {@code application.yml}. HS256 verifies with the same key it signs with, so
 * the committed value let anyone with repository access mint a token for any
 * user, role and workspace. RS256 splits the two, and {@code kid} in the token
 * header lets several keys be trusted at once — which is what makes the
 * 90-day-rotation-with-24-hour-overlap policy in
 * {@code docs/SECRETS-MANAGEMENT.md} section 4 implementable rather than merely
 * stated.
 *
 * <p>Rotation is exposed as {@link #rotate()} and is deliberately not on a
 * timer. Every replica runs its own schedule, so a timed rotation across more
 * than one instance would race and produce divergent active keys; that needs a
 * distributed lock, which is on the deferred list in plan 0007. The mechanism
 * and the overlap behaviour are implemented and tested here, so adding the
 * cadence later is a scheduling concern rather than a cryptographic one.
 */
@Slf4j
public class JwtKeyProvider {

    private static final int KEY_SIZE_BITS = 2048;

    private final JwtKeyStore keyStore;
    private final Duration rotationPeriod;
    private final Duration overlap;
    private final Clock clock;
    private final AtomicReference<JwtKeyRing> keyRing = new AtomicReference<>();

    public JwtKeyProvider(JwtKeyStore keyStore, Duration rotationPeriod, Duration overlap, Clock clock) {
        this.keyStore = keyStore;
        this.rotationPeriod = rotationPeriod;
        this.overlap = overlap;
        this.clock = clock;

        JwtKeyRing loaded = keyStore.load().orElse(null);
        if (loaded == null) {
            JwtKeyRing generated = new JwtKeyRing(generateKey(), java.util.List.of());
            keyStore.save(generated);
            keyRing.set(generated);
            log.info("No existing JWT key ring found; generated a new signing key (kid={}){}",
                    generated.active().kid(),
                    keyStore.isDurable() ? "" : " — this store is not durable, so tokens will not "
                            + "survive a restart");
        } else {
            keyRing.set(loaded);
            Duration age = Duration.between(loaded.active().createdAt(), clock.instant());
            log.info("Loaded JWT key ring (active kid={}, age {} days, {} retired key(s) still accepted)",
                    loaded.active().kid(), age.toDays(), loaded.retired().size());
            if (age.compareTo(rotationPeriod) > 0) {
                // Stated rather than acted on: silently rotating at startup
                // would invalidate tokens at an arbitrary moment, and on a
                // multi-replica deployment each replica would do it separately.
                log.warn("The active JWT signing key is {} days old, beyond the configured rotation period "
                                + "of {} days. Rotate it via JwtKeyProvider.rotate().",
                        age.toDays(), rotationPeriod.toDays());
            }
        }
    }

    /** The key new tokens are signed with. */
    public JwtKeyRing.Key signingKey() {
        return keyRing.get().active();
    }

    /**
     * The key a token's {@code kid} refers to, if it is still trusted.
     *
     * <p>Empty means reject: either the {@code kid} is unknown, or the key was
     * retired longer ago than the overlap window allows. A token with no
     * {@code kid} at all — such as one signed with the old symmetric key — also
     * lands here and is refused.
     */
    public Optional<JwtKeyRing.Key> verificationKey(String kid) {
        return keyRing.get().verificationKey(kid, clock.instant(), overlap);
    }

    /**
     * Generates a new signing key and retires the current one, which stays
     * verifiable for the overlap window so tokens already issued keep working.
     *
     * @return the new active key
     */
    public synchronized JwtKeyRing.Key rotate() {
        JwtKeyRing current = keyRing.get();
        JwtKeyRing rotated = current.rotateTo(generateKey(), clock.instant(), overlap);
        keyStore.save(rotated);
        keyRing.set(rotated);

        log.info("Rotated JWT signing key: {} -> {}. The previous key remains valid for {} hours.",
                current.active().kid(), rotated.active().kid(), overlap.toHours());
        return rotated.active();
    }

    /** Exposed for diagnostics and tests; never logged or returned to a client. */
    public JwtKeyRing currentKeyRing() {
        return keyRing.get();
    }

    private JwtKeyRing.Key generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            KeyPair pair = generator.generateKeyPair();
            return new JwtKeyRing.Key(
                    UUID.randomUUID().toString(),
                    pair.getPrivate(),
                    pair.getPublic(),
                    clock.instant(),
                    null);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation is unavailable on this JVM", e);
        }
    }
}
