package com.symphony.config;

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

    /**
     * The JWT signing key committed to {@code application.yml}. HS256 is
     * symmetric, so anyone with repository access can mint tokens for any user,
     * role and workspace while this value is in use.
     */
    static final String KNOWN_DEFAULT_JWT_SECRET =
            "dGhpcy1pcy1hLXNlY3JldC1rZXktZm9yLXN5bXBob255LWFpLWp3dC10b2tlbi1nZW5lcmF0aW9uLXdpdGgtMzItYnl0ZXM=";

    /** The development vault key hardcoded in {@code LocalSecretsVaultService}. */
    static final String KNOWN_DEFAULT_VAULT_KEY = "this-is-a-32-byte-encryption-key-for-local-vault";

    /** Sentinel that routes every LLM and embedding call down the offline mock path. */
    static final String MOCK_API_KEY = "mock-key";

    private final MockModeProperties mockMode;
    private final SecretsVaultService secretsVaultService;

    @Value("${openai.api-key:}")
    private String openAiApiKey;

    @Value("${symphony.jwt.secret:}")
    private String jwtSecret;

    @Value("${symphony.vault.encryption-key:" + KNOWN_DEFAULT_VAULT_KEY + "}")
    private String vaultEncryptionKey;

    public ConfigurationGuard(MockModeProperties mockMode, SecretsVaultService secretsVaultService) {
        this.mockMode = mockMode;
        this.secretsVaultService = secretsVaultService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mockMode.isEnabled()) {
            logMockModeBanner();
            return;
        }

        List<String> violations = findViolations(
                openAiApiKey, jwtSecret, vaultEncryptionKey, secretsVaultService.isDurable());

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
    static List<String> findViolations(String openAiApiKey, String jwtSecret, String vaultEncryptionKey,
                                       boolean vaultIsDurable) {
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

        if (isBlank(jwtSecret)) {
            violations.add("symphony.jwt.secret is not set. Set JWT_SECRET to key material held outside "
                    + "this repository.");
        } else if (KNOWN_DEFAULT_JWT_SECRET.equals(jwtSecret.trim())) {
            violations.add("symphony.jwt.secret is the default value committed to application.yml. "
                    + "HS256 is symmetric, so anyone with repository access can mint a token for any user, "
                    + "role and workspace. Set JWT_SECRET to key material held outside this repository.");
        }

        if (isBlank(vaultEncryptionKey) || KNOWN_DEFAULT_VAULT_KEY.equals(vaultEncryptionKey.trim())) {
            violations.add("symphony.vault.encryption-key is unset or still the hardcoded development key, "
                    + "so stored agent credentials are readable by anyone with this repository. "
                    + "Supply a key from the environment.");
        }

        if (!vaultIsDurable) {
            violations.add("The active SecretsVaultService does not survive a restart. Agent credentials "
                    + "would be lost while the agents.credential_vault_uri rows pointing at them persist, "
                    + "leaving dangling references. A durable, KMS-backed implementation is phase 4 of "
                    + "plan 0007 and is not yet available, so production mode cannot currently be satisfied.");
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
