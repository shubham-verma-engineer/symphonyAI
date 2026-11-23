package com.symphony.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultVersionedKeyValueOperations;
import org.springframework.vault.support.Versioned;

import java.util.Map;

/**
 * Durable {@link SecretsVaultService} backed by a HashiCorp Vault KV v2 mount.
 *
 * <p>Replaces {@code LocalSecretsVaultService} outside mock mode. That
 * implementation kept ciphertext in an in-process map, so every agent
 * credential was lost on restart while the {@code agents.credential_vault_uri}
 * rows pointing at them survived — leaving references that resolved to nothing
 * and surfaced much later as unexplained 401s from agents whose manifests
 * looked healthy.
 *
 * <p>Stored URIs take the form {@code vault://<mount>/<path>}, recording the
 * mount a secret was written to so that changing the configured mount does not
 * silently redirect reads for existing rows. The previous in-memory
 * implementation wrote {@code vault:/<path>} with no mount; such a URI is
 * reported as unresolvable rather than guessed at, which is what
 * {@link #isResolvable(String)} exists to let callers check.
 */
@Slf4j
public class HashiCorpSecretsVaultService implements SecretsVaultService {

    /** Field name holding the secret inside the KV document. */
    private static final String SECRET_FIELD = "value";

    private static final String URI_SCHEME = "vault://";

    private final VaultTemplate vaultTemplate;
    private final String defaultMount;

    public HashiCorpSecretsVaultService(VaultTemplate vaultTemplate, String defaultMount) {
        this.vaultTemplate = vaultTemplate;
        this.defaultMount = stripSlashes(defaultMount);
    }

    /** Secrets live in Vault's own storage and survive restarts of this application. */
    @Override
    public boolean isDurable() {
        return true;
    }

    @Override
    public String storeSecret(String path, String secretValue) {
        if (secretValue == null) {
            return null;
        }
        String key = stripSlashes(path);
        kv(defaultMount).put(key, Map.of(SECRET_FIELD, secretValue));

        // Log the location, never the value.
        log.info("Stored agent credential in Vault at {}/{}", defaultMount, key);
        return URI_SCHEME + defaultMount + "/" + key;
    }

    @Override
    public String retrieveSecret(String vaultUri) {
        MountedPath mountedPath = parse(vaultUri);
        if (mountedPath == null) {
            return null;
        }

        try {
            Versioned<Map<String, Object>> versioned =
                    kv(mountedPath.mount()).get(mountedPath.path());
            if (versioned == null || !versioned.hasData() || versioned.getData() == null) {
                log.warn("No secret found in Vault at {}", vaultUri);
                return null;
            }
            Object value = versioned.getData().get(SECRET_FIELD);
            return value == null ? null : value.toString();
        } catch (RuntimeException e) {
            // Deliberately not rethrown: a credential that cannot be read is a
            // condition for the caller to handle (the agent is marked
            // UNHEALTHY), not a reason to fail the whole request with a stack
            // trace that may carry request context into the logs.
            log.error("Failed to read secret from Vault at {}: {}", vaultUri, e.getMessage());
            return null;
        }
    }

    @Override
    public void deleteSecret(String vaultUri) {
        MountedPath mountedPath = parse(vaultUri);
        if (mountedPath == null) {
            return;
        }
        try {
            kv(mountedPath.mount()).delete(mountedPath.path());
            log.info("Deleted secret from Vault at {}", vaultUri);
        } catch (RuntimeException e) {
            log.error("Failed to delete secret from Vault at {}: {}", vaultUri, e.getMessage());
        }
    }

    /**
     * Whether a stored URI can be read by this implementation at all.
     *
     * <p>Distinguishes "this URI does not address anything we can reach" — a
     * legacy {@code vault:/…} row written by the in-memory implementation, or a
     * malformed value — from "the URI is valid but the secret is missing". The
     * first is a provisioning problem the operator must resolve by re-onboarding
     * the agent; the second may be transient.
     */
    public boolean isResolvable(String vaultUri) {
        return parse(vaultUri) != null;
    }

    private VaultVersionedKeyValueOperations kv(String mount) {
        return vaultTemplate.opsForVersionedKeyValue(mount);
    }

    /** {@code vault://<mount>/<path>} split into its parts, or null if unparseable. */
    private MountedPath parse(String vaultUri) {
        if (vaultUri == null || !vaultUri.startsWith(URI_SCHEME)) {
            return null;
        }
        String remainder = stripSlashes(vaultUri.substring(URI_SCHEME.length()));
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1) {
            return null;
        }
        return new MountedPath(remainder.substring(0, slash), remainder.substring(slash + 1));
    }

    private static String stripSlashes(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record MountedPath(String mount, String path) {}
}
