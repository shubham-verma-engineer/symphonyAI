package com.symphony.config;

import com.symphony.config.jwt.JwtKeyStore;
import com.symphony.service.SecretsVaultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start when production mode is paired with development configuration.
 *
 * <p>Plan 0007 catalogues six defects that blocked deployment. What made them
 * dangerous was not their individual severity but that every one of them failed
 * <em>silently</em>: a missing {@code OPENAI_API_KEY} produced confident search
 * rankings computed from a seeded PRNG, a committed signing key looked identical
 * to a real one, and an in-memory vault reported success right up until the next
 * restart. A deployment could come up healthy and serve fabricated results.
 *
 * <p>This component converts each of those into a startup failure. Validation
 * lives in one place rather than being scattered across the classes that consume
 * the values, so the full set of problems is reported in a single message instead
 * of one per restart. Failing at startup is the only reliable guard available
 * here, precisely because none of these defects announces itself at runtime.
 *
 * <p>In mock mode every check is skipped and a prominent banner is logged, so
 * that mode can never be mistaken for a real deployment when reading logs.
 */
@Component
@Slf4j
public class ConfigurationGuard implements ApplicationRunner {

    /** The development vault key hardcoded in {@code LocalSecretsVaultService}. */
    static final String KNOWN_DEFAULT_VAULT_KEY = "this-is-a-32-byte-encryption-key-for-local-vault";

    /** Sentinel that routes every LLM and embedding call down the offline mock path. */
    static final String MOCK_API_KEY = "mock-key";

    private final MockModeProperties mockMode;
    private final SecretsVaultService secretsVaultService;
    private final JwtKeyStore jwtKeyStore;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${symphony.vault.encryption-key:" + KNOWN_DEFAULT_VAULT_KEY + "}")
    private String vaultEncryptionKey;

    public ConfigurationGuard(MockModeProperties mockMode, SecretsVaultService secretsVaultService,
                              JwtKeyStore jwtKeyStore) {
        this.mockMode = mockMode;
        this.secretsVaultService = secretsVaultService;
        this.jwtKeyStore = jwtKeyStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mockMode.isEnabled()) {
            logMockModeBanner();
            return;
        }

        List<String> violations = findViolations(
                openAiApiKey, vaultEncryptionKey, secretsVaultService.isDurable(), jwtKeyStore.isDurable());

        if (!violations.isEmpty()) {
            StringBuilder message = new StringBuilder(
                    "\n"
                    + "***************************************************************\n"
                    + "REFUSING TO START: production configuration is incomplete\n"
                    + "***************************************************************\n");
            for (int i = 0; i < violations.size(); i++) {
                message.append(String.format("%n  %d. %s%n", i + 1, violations.get(i)));
            }
            message.append("\nTo run locally with offline stand-ins for all of the above, set:\n")
                    .append("  SYMPHONY_MOCK_ENABLED=true\n")
                    .append("\nSee docs/implementation-plans/0007-production-hardening-blockers.md\n");

            throw new IllegalStateException(message.toString());
        }

        log.info("Configuration guard passed: running in production mode with no development defaults in use.");
    }

    /**
     * Returns one human-readable violation per unsatisfied production invariant.
     *
     * <p>Takes its inputs as arguments rather than reading fields so the rules
     * can be exercised directly, without standing up an application context.
     * Every message names the offending property and what is expected, because a
     * guard that blocks a deployment without saying why gets disabled rather
     * than fixed.
     */
    static List<String> findViolations(String openAiApiKey, String vaultEncryptionKey,
                                       boolean vaultIsDurable, boolean jwtKeyStoreIsDurable) {
        List<String> violations = new ArrayList<>();

        if (isBlank(openAiApiKey)) {
            violations.add("openai.api-key is not set. Set OPENAI_API_KEY to a real key. "
                    + "Leaving it unset previously fell back to the offline mock path, which answers "
                    + "semantic searches with vectors from a seeded PRNG.");
        } else if (MOCK_API_KEY.equalsIgnoreCase(openAiApiKey.trim())) {
            violations.add("openai.api-key is the literal \"" + MOCK_API_KEY + "\", which selects the offline "
                    + "mock path: seeded embeddings, canned parameter extraction and bypassed agent calls. "
                    + "Set OPENAI_API_KEY to a real key.");
        }

        if (!jwtKeyStoreIsDurable) {
            violations.add("JWT signing keys are held in an ephemeral in-memory store, so every restart "
                    + "would invalidate all issued tokens and sign every user out. Point "
                    + "symphony.vault.provider at hashicorp so the key ring is persisted and shared "
                    + "across replicas.");
        }

        if (isBlank(vaultEncryptionKey) || KNOWN_DEFAULT_VAULT_KEY.equals(vaultEncryptionKey.trim())) {
            violations.add("symphony.vault.encryption-key is unset or still the hardcoded development key, "
                    + "so stored agent credentials are readable by anyone with this repository. "
                    + "Supply a key from the environment.");
        }

        if (!vaultIsDurable) {
            violations.add("The active SecretsVaultService does not survive a restart. Agent credentials "
                    + "would be lost while the agents.credential_vault_uri rows pointing at them persist, "
                    + "leaving dangling references. Set symphony.vault.provider=hashicorp and supply "
                    + "VAULT_URI and VAULT_TOKEN.");
        }

        return violations;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void logMockModeBanner() {
        log.warn("\n"
                + "***************************************************************\n"
                + "  MOCK MODE ENABLED - symphony.mock.enabled=true\n"
                + "***************************************************************\n"
                + "  This instance is NOT production-safe:\n"
                + "    - LLM routing and embeddings are deterministic local fakes\n"
                + "    - Outbound agent calls are bypassed, not performed\n"
                + "    - Agent health probes are skipped; agents report ACTIVE\n"
                + "    - Stored agent credentials are lost on restart\n"
                + "    - Development accounts are seeded with a shared password\n"
                + "  Unset SYMPHONY_MOCK_ENABLED for a real deployment.\n"
                + "***************************************************************");
    }
}
