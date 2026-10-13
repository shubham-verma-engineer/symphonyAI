package com.symphony.config.jwt;

import java.util.Optional;

/**
 * Non-persistent {@link JwtKeyStore} for local development.
 *
 * <p>Each boot generates a fresh key, so no key material is ever written to
 * disk or committed — which is the point: the old arrangement shipped a working
 * HS256 key in {@code application.yml}. The cost is that a restart invalidates
 * every issued token, so anyone signed in must sign in again. That is fine
 * locally and is why {@code JwtKeyProvider} reports the store as non-durable at
 * startup.
 */
public class InMemoryJwtKeyStore implements JwtKeyStore {

    private JwtKeyRing keyRing;

    @Override
    public Optional<JwtKeyRing> load() {
        return Optional.ofNullable(keyRing);
    }

    @Override
    public void save(JwtKeyRing keyRing) {
        this.keyRing = keyRing;
    }

    @Override
    public boolean isDurable() {
        return false;
    }
}
