package com.symphony.config.jwt;

import java.util.Optional;

/**
 * Where a {@link JwtKeyRing} is persisted between restarts.
 *
 * <p>Separated from {@link JwtKeyProvider} so that key handling — generation,
 * selection, rotation — is testable without any storage, and so the production
 * store can be swapped without touching that logic.
 */
public interface JwtKeyStore {

    /** Loads the stored ring, or empty if none has been written yet. */
    Optional<JwtKeyRing> load();

    /** Persists {@code keyRing}, replacing whatever was stored. */
    void save(JwtKeyRing keyRing);

    /**
     * Whether the ring survives a restart.
     *
     * <p>A non-durable store means every restart invalidates all issued tokens,
     * which is acceptable locally and not in production.
     */
    default boolean isDurable() {
        return false;
    }
}
