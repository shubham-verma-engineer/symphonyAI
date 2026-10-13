package com.symphony.config.jwt;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The set of RSA keys a running instance will sign with and accept.
 *
 * <p>One key is active and signs new tokens. Previously active keys stay
 * verifiable for an overlap window so that tokens already in circulation are
 * not invalidated the moment a key is rotated — the mechanism
 * {@code docs/SECRETS-MANAGEMENT.md} section 4 requires for its 90-day rotation
 * with 24-hour graceful drainage, and the reason this moved off symmetric HS256,
 * where the verifying key is also a signing key.
 *
 * <p>Immutable: rotation produces a new ring rather than mutating this one, so a
 * request that is mid-verification cannot observe a half-rotated state.
 */
public record JwtKeyRing(Key active, List<Key> retired) {

    /**
     * One key in the ring.
     *
     * @param kid        key id, carried in the JWT header so a verifier can pick
     *                   the right key instead of trying all of them
     * @param privateKey signing key; null for a retired key, which is
     *                   verify-only
     * @param publicKey  verification key
     * @param createdAt  when the key became active, which is what the rotation
     *                   period is measured against
     * @param retiredAt  when the key stopped signing; null while active
     */
    public record Key(String kid, PrivateKey privateKey, PublicKey publicKey,
                      Instant createdAt, Instant retiredAt) {

        /** Whether this key may still verify, given the overlap window. */
        public boolean isAcceptableAt(Instant now, java.time.Duration overlap) {
            return retiredAt == null || now.isBefore(retiredAt.plus(overlap));
        }
    }

    public JwtKeyRing {
        retired = retired == null ? List.of() : List.copyOf(retired);
    }

    /**
     * Finds the key a given {@code kid} refers to, provided it is still
     * acceptable. An unknown {@code kid}, or one whose overlap window has
     * closed, yields empty — and the caller rejects the token.
     */
    public Optional<Key> verificationKey(String kid, Instant now, java.time.Duration overlap) {
        if (kid == null) {
            return Optional.empty();
        }
        if (kid.equals(active.kid())) {
            return Optional.of(active);
        }
        return retired.stream()
                .filter(k -> kid.equals(k.kid()))
                .filter(k -> k.isAcceptableAt(now, overlap))
                .findFirst();
    }

    /**
     * Returns a ring with {@code newActive} signing and the current active key
     * retired as of {@code now}. Keys whose overlap has already closed are
     * dropped, so the ring does not grow without bound.
     */
    public JwtKeyRing rotateTo(Key newActive, Instant now, java.time.Duration overlap) {
        List<Key> stillUseful = new ArrayList<>();
        stillUseful.add(new Key(active.kid(), null, active.publicKey(), active.createdAt(), now));
        retired.stream()
                .filter(k -> k.isAcceptableAt(now, overlap))
                .forEach(stillUseful::add);
        return new JwtKeyRing(newActive, stillUseful);
    }
}
