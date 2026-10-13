package com.symphony;

import com.symphony.config.ConfigurationGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 of plan 0007: production mode must refuse development configuration.
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
    private static final String REAL_JWT = "YW5vdGhlci1zZWNyZXQta2V5LXRoYXQtaXMtbm90LXRoZS1jb21taXR0ZWQtZGVmYXVsdA==";
    private static final String REAL_VAULT_KEY = "a-key-supplied-by-the-environment";

    private static final String COMMITTED_JWT_DEFAULT =
            "dGhpcy1pcy1hLXNlY3JldC1rZXktZm9yLXN5bXBob255LWFpLWp3dC10b2tlbi1nZW5lcmF0aW9uLXdpdGgtMzItYnl0ZXM=";
    private static final String HARDCODED_VAULT_DEFAULT = "this-is-a-32-byte-encryption-key-for-local-vault";

    @SuppressWarnings("unchecked")
    private static List<String> violations(String apiKey, String jwt, String vaultKey, boolean durable) {
        try {
            Method m = ConfigurationGuard.class.getDeclaredMethod(
                    "findViolations", String.class, String.class, String.class, boolean.class);
            m.setAccessible(true);
            return (List<String>) m.invoke(null, apiKey, jwt, vaultKey, durable);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("findViolations is not reachable with the expected signature", e);
        }
    }

    @Test
    void fullyConfiguredProductionSetupPasses() {
        assertTrue(violations(REAL_KEY, REAL_JWT, REAL_VAULT_KEY, true).isEmpty(),
                "A complete production configuration should produce no violations");
    }

    @Test
    void missingOpenAiKeyIsRejected() {
        for (String absent : new String[]{null, "", "   "}) {
            List<String> v = violations(absent, REAL_JWT, REAL_VAULT_KEY, true);
            assertEquals(1, v.size(), "Expected exactly one violation for key: " + absent);
            assertTrue(v.get(0).contains("openai.api-key"), v.get(0));
        }
    }

    @Test
    void literalMockKeyIsRejectedAndExplainsWhy() {
        List<String> v = violations("mock-key", REAL_JWT, REAL_VAULT_KEY, true);
        assertEquals(1, v.size());
        // The message must say what the mock path actually does, since the
        // failure it prevents is silent and plausible-looking.
        assertTrue(v.get(0).contains("mock"), v.get(0));
        assertTrue(v.get(0).contains("OPENAI_API_KEY"), v.get(0));
    }

    @Test
    void mockKeyIsRejectedRegardlessOfCaseOrSurroundingWhitespace() {
        for (String variant : new String[]{"MOCK-KEY", "Mock-Key", "  mock-key  "}) {
            assertFalse(violations(variant, REAL_JWT, REAL_VAULT_KEY, true).isEmpty(),
                    "Should have been rejected: '" + variant + "'");
        }
    }

    @Test
    void committedJwtSecretIsRejected() {
        List<String> v = violations(REAL_KEY, COMMITTED_JWT_DEFAULT, REAL_VAULT_KEY, true);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("symphony.jwt.secret"), v.get(0));
        assertTrue(v.get(0).contains("symmetric"), "Should explain why a committed HS256 key matters: " + v.get(0));
    }

    @Test
    void missingJwtSecretIsRejected() {
        List<String> v = violations(REAL_KEY, null, REAL_VAULT_KEY, true);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("JWT_SECRET"), v.get(0));
    }

    @Test
    void hardcodedVaultKeyIsRejected() {
        List<String> v = violations(REAL_KEY, REAL_JWT, HARDCODED_VAULT_DEFAULT, true);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("symphony.vault.encryption-key"), v.get(0));
    }

    @Test
    void nonDurableVaultIsRejected() {
        List<String> v = violations(REAL_KEY, REAL_JWT, REAL_VAULT_KEY, false);
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("restart"), v.get(0));
        assertTrue(v.get(0).contains("phase 4"), "Should point at the work that fixes it: " + v.get(0));
    }

    @Test
    void everyProblemIsReportedTogetherRatherThanOnePerRestart() {
        // A guard that surfaced one problem per boot would turn a single
        // misconfiguration into four deploy-and-retry cycles.
        List<String> v = violations(null, COMMITTED_JWT_DEFAULT, HARDCODED_VAULT_DEFAULT, false);
        assertEquals(4, v.size(), "Expected all four invariants to be reported at once, got: " + v);
    }

    @Test
    void everyViolationMessageNamesAPropertyOrEnvironmentVariable() {
        // A blocked deployment with no stated cause gets the guard disabled
        // rather than the configuration fixed.
        for (String message : violations(null, null, null, false)) {
            assertTrue(message.contains("symphony.") || message.contains("openai.")
                            || message.contains("OPENAI_API_KEY") || message.contains("JWT_SECRET")
                            || message.contains("SecretsVaultService"),
                    "Violation does not identify what to change: " + message);
        }
    }
}
