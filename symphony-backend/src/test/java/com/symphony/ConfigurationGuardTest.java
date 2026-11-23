package com.symphony;

import com.symphony.config.ConfigurationGuard;
import com.symphony.config.MockModeProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production mode must refuse development configuration (plan 0007, phases 1, 5
 * and 6).
 *
 * <p>Exercises the invariant rules directly rather than through an application
 * context. The guard's whole purpose is to fail a boot, and a test that needed a
 * successful boot to check it could not cover the failing cases at all.
 *
 * <p>{@code findViolations} is package-private on {@code ConfigurationGuard},
 * which sits in {@code com.symphony.config} while this test is in
 * {@code com.symphony}, so it is reached reflectively. That keeps the method off
 * the public surface — nothing outside the guard should be making these
 * judgements.
 */
public class ConfigurationGuardTest {

    private static final String REAL_KEY = "sk-proj-a-real-looking-api-key";
    private static final String VAULT_URI = "https://vault.internal:8200";
    private static final String VAULT_TOKEN = "s.a-real-looking-token";

    @SuppressWarnings("unchecked")
    private static List<String> violations(String apiKey, boolean llmMocked, String provider,
                                           String vaultUri, String vaultToken) {
        try {
            Method m = ConfigurationGuard.class.getDeclaredMethod("findViolations",
                    String.class, boolean.class, String.class, String.class, String.class);
            m.setAccessible(true);
            return (List<String>) m.invoke(null, apiKey, llmMocked, provider, vaultUri, vaultToken);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("findViolations is not reachable with the expected signature", e);
        }
    }

    /** A complete production configuration, varied one field at a time below. */
    private static List<String> production() {
        return violations(REAL_KEY, false, "", VAULT_URI, VAULT_TOKEN);
    }

    @Test
    void fullyConfiguredProductionSetupPasses() {
        assertTrue(production().isEmpty(),
                "A complete production configuration should produce no violations");
    }

    @Test
    void providerDefaultsToHashicorpOutsideMockMode() {
        // Leaving symphony.vault.provider unset in production must mean "Vault",
        // and so must require the Vault connection settings.
        assertTrue(violations(REAL_KEY, false, "", VAULT_URI, VAULT_TOKEN).isEmpty());
        assertEquals(2, violations(REAL_KEY, false, "", "", "").size());
    }

    @Test
    void missingOpenAiKeyIsRejected() {
        for (String absent : new String[]{null, "", "   "}) {
            List<String> v = violations(absent, false, "", VAULT_URI, VAULT_TOKEN);
            assertEquals(1, v.size(), "Expected exactly one violation for key: " + absent);
            assertTrue(v.get(0).contains("OPENAI_API_KEY"), v.get(0));
        }
    }

    @Test
    void mockedLlmIsRejectedAndExplainsWhy() {
        // Phase 6 replaced the "mock-key" sentinel with an explicit setting, so
        // the dangerous state is checked directly rather than inferred from a
        // key value.
        List<String> v = violations(REAL_KEY, true, "", VAULT_URI, VAULT_TOKEN);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("symphony.mock.llm"), v.get(0));
        assertTrue(v.get(0).contains("PRNG"),
                "Should say why a mocked LLM is dangerous — it fails silently: " + v.get(0));
    }

    @Test
    void mockedLlmIsReportedInsteadOfAMissingKeyNotAlongsideIt() {
        // With the LLM mocked the key is never used, so complaining about it
        // too would send the operator after the wrong fix.
        List<String> v = violations(null, true, "", VAULT_URI, VAULT_TOKEN);
        assertEquals(1, v.size(), "Only the mocked-LLM violation should be reported: " + v);
        assertTrue(v.get(0).contains("symphony.mock.llm"), v.get(0));
    }

    @Test
    void theOldMockKeySentinelIsNowJustAKey() {
        // "mock-key" no longer selects any behaviour. Set in production it is
        // sent to OpenAI and rejected there — a loud failure — so the guard has
        // no reason to treat it specially.
        assertTrue(violations("mock-key", false, "", VAULT_URI, VAULT_TOKEN).isEmpty());
    }

    @Test
    void localVaultProviderIsRejectedAsOneProblemNotTwo() {
        // Both agent credentials and JWT signing keys follow the provider, and
        // one setting fixes both — so it is reported once.
        List<String> v = violations(REAL_KEY, false, "local", VAULT_URI, VAULT_TOKEN);
        assertEquals(1, v.size(), "Expected a single violation, got: " + v);
        assertTrue(v.get(0).contains("JWT signing keys"), v.get(0));
        assertTrue(v.get(0).contains("credentials"), v.get(0));
        assertTrue(v.get(0).contains("SYMPHONY_VAULT_PROVIDER"), v.get(0));
    }

    @Test
    void missingVaultConnectionSettingsAreRejected() {
        List<String> v = violations(REAL_KEY, false, "hashicorp", "", "");
        assertEquals(2, v.size(), "Expected uri and token reported separately: " + v);
        assertTrue(v.stream().anyMatch(m -> m.contains("VAULT_URI")), v.toString());
        assertTrue(v.stream().anyMatch(m -> m.contains("VAULT_TOKEN")), v.toString());
    }

    @Test
    void unrecognisedProviderIsRejectedRatherThanDefaulted() {
        List<String> v = violations(REAL_KEY, false, "hashicrop", VAULT_URI, VAULT_TOKEN);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("hashicrop"), v.get(0));
    }

    @Test
    void vaultEncryptionKeyIsNotRequiredInProduction() {
        // Regression: the guard used to demand a non-default
        // symphony.vault.encryption-key even with a durable Vault, but only the
        // in-memory vault reads that key and it is refused in production anyway.
        // A correctly configured Vault deployment was blocked over a setting it
        // never uses. The signature no longer takes the key at all.
        assertTrue(production().isEmpty());
    }

    @Test
    void everyProblemIsReportedTogetherRatherThanOnePerRestart() {
        // The regression this guards: an unconfigured production boot used to
        // die constructing the Vault client, reporting only the Vault URI. The
        // operator then learned about the OpenAI key on the next attempt.
        List<String> v = violations(null, false, "", "", "");
        assertEquals(3, v.size(), "Expected key, URI and token reported at once, got: " + v);
    }

    @Test
    void everyViolationMessageNamesAPropertyOrEnvironmentVariable() {
        // A blocked deployment with no stated cause gets the guard disabled
        // rather than the configuration fixed.
        for (String provider : new String[]{"", "local", "hashicorp"}) {
            for (boolean llmMocked : new boolean[]{true, false}) {
                for (String message : violations(null, llmMocked, provider, "", "")) {
                    assertTrue(message.contains("symphony.") || message.contains("OPENAI_API_KEY")
                                    || message.contains("VAULT_"),
                            "Violation does not identify what to change: " + message);
                }
            }
        }
    }

    @Test
    void guardAndServicesUseTheSameLlmRule() {
        // The guard reads raw configuration before beans exist; services ask the
        // bound properties. They must never disagree.
        for (String setting : new String[]{null, "", " ", "true", "false", "TRUE"}) {
            for (boolean enabled : new boolean[]{true, false}) {
                MockModeProperties props = new MockModeProperties();
                props.setEnabled(enabled);
                if (setting != null && !setting.isBlank()) {
                    props.setLlm(Boolean.parseBoolean(setting.trim()));
                }
                assertEquals(props.isLlmMocked(), MockModeProperties.resolveLlmMocked(setting, enabled),
                        "Disagreement for llm='" + setting + "', enabled=" + enabled);
            }
        }
    }

    @Test
    void llmMockingFollowsMockModeUnlessOverridden() {
        MockModeProperties props = new MockModeProperties();

        props.setEnabled(false);
        assertFalse(props.isLlmMocked(), "Production default: real LLM");

        props.setEnabled(true);
        assertTrue(props.isLlmMocked(), "Mock mode default: mocked LLM");

        props.setLlm(false);
        assertFalse(props.isLlmMocked(), "Explicit override: real LLM inside mock mode");

        props.setEnabled(false);
        props.setLlm(true);
        assertTrue(props.isLlmMocked(), "Explicit override wins even outside mock mode — "
                + "which is exactly the combination the guard then refuses");
    }
}
