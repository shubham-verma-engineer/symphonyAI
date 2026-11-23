package com.symphony.service;

public interface SecretsVaultService {
    /**
     * Encrypts and stores a secret at a logical vault path.
     *
     * @param path The vault path to register the secret under (e.g. "/symphony/agents/123/token").
     * @param secretValue The plaintext secret key/value.
     * @return The unique reference URI pointing to the secret stored in the vault.
     */
    String storeSecret(String path, String secretValue);

    /**
     * Retrieves and decrypts the secret at the specified vault URI.
     *
     * @param vaultUri The reference URI of the secret.
     * @return The decrypted plaintext secret.
     */
    String retrieveSecret(String vaultUri);

    /**
     * Deletes the secret matching the specified vault URI.
     *
     * @param vaultUri The reference URI of the secret.
     */
    void deleteSecret(String vaultUri);

    /**
     * Whether secrets stored here survive an application restart.
     *
     * <p>Defaults to {@code false}: an implementation must positively declare
     * durability, so a new in-memory or test double is never mistaken for one.
     * {@code ConfigurationGuard} refuses to start in production mode while the
     * active implementation is non-durable, because losing agent credentials on
     * restart leaves the {@code agents.credential_vault_uri} rows pointing at
     * secrets that no longer exist.
     */
    default boolean isDurable() {
        return false;
    }
}
