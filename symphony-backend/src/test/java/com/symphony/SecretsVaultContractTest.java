package com.symphony;

import com.symphony.service.HashiCorpSecretsVaultService;
import com.symphony.service.LocalSecretsVaultService;
import com.symphony.service.SecretsVaultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One contract, both {@link SecretsVaultService} implementations.
 *
 * <p>Phase 4 of plan 0007 introduces a second implementation. Two independently
 * tested implementations of one interface drift, and the way they drift here
 * matters: {@code AgentService} and {@code SupervisorService} are written
 * against the interface and must behave identically whichever is wired. So the
 * behavioural expectations live in one abstract parent and each implementation
 * is made to satisfy them.
 *
 * <p>The Vault-backed case needs a reachable Vault. Rather than failing a suite
 * on a machine that has none, it is skipped via an assumption — a skip is
 * visible in the report, whereas quietly passing would not be. Start one with:
 *
 * <pre>
 * docker run -d --name symphony-vault --cap-add=IPC_LOCK \
 *   -e VAULT_DEV_ROOT_TOKEN_ID=symphony-dev-root-token \
 *   -p 8200:8200 hashicorp/vault:1.17
 * </pre>
 */
public class SecretsVaultContractTest {

    private static final String VAULT_URI = System.getenv().getOrDefault("VAULT_URI", "http://localhost:8200");
    private static final String VAULT_TOKEN =
            System.getenv().getOrDefault("VAULT_TOKEN", "symphony-dev-root-token");

    /** The expectations every implementation must meet. */
    abstract static class VaultContract {

        abstract SecretsVaultService vault();

        private static String uniquePath() {
            return "symphony/agents/" + UUID.randomUUID() + "/token";
        }

        @Test
        @DisplayName("a stored secret reads back unchanged")
        void storedSecretReadsBackUnchanged() {
            String secret = "sk-agent-token-" + UUID.randomUUID();
            String uri = vault().storeSecret(uniquePath(), secret);

            assertNotNull(uri, "storeSecret must return a reference URI");
            assertEquals(secret, vault().retrieveSecret(uri));
        }

        @Test
        @DisplayName("the returned URI never contains the secret itself")
        void returnedUriDoesNotLeakTheSecret() {
            // The URI is persisted in agents.credential_vault_uri in plaintext,
            // so it must be a pointer and nothing more.
            String secret = "super-secret-value-" + UUID.randomUUID();
            String uri = vault().storeSecret(uniquePath(), secret);

            assertFalse(uri.contains(secret), "Reference URI leaked the secret: " + uri);
        }

        @Test
        @DisplayName("distinct paths hold independent secrets")
        void distinctPathsAreIndependent() {
            String firstUri = vault().storeSecret(uniquePath(), "first-secret");
            String secondUri = vault().storeSecret(uniquePath(), "second-secret");

            assertNotEquals(firstUri, secondUri);
            assertEquals("first-secret", vault().retrieveSecret(firstUri));
            assertEquals("second-secret", vault().retrieveSecret(secondUri));
        }

        @Test
        @DisplayName("a deleted secret no longer resolves")
        void deletedSecretNoLongerResolves() {
            String uri = vault().storeSecret(uniquePath(), "to-be-deleted");
            assertEquals("to-be-deleted", vault().retrieveSecret(uri));

            vault().deleteSecret(uri);
            assertNull(vault().retrieveSecret(uri),
                    "A deleted secret must not continue to resolve");
        }

        @Test
        @DisplayName("an unknown URI returns null rather than throwing")
        void unknownUriReturnsNull() {
            // Callers treat null as "credential unavailable" and mark the agent
            // UNHEALTHY. Throwing instead would surface as a request failure
            // far from the cause.
            assertNull(vault().retrieveSecret("vault://secret/symphony/agents/"
                    + UUID.randomUUID() + "/token"));
        }

        @Test
        @DisplayName("a null secret is not stored")
        void nullSecretIsNotStored() {
            assertNull(vault().storeSecret(uniquePath(), null));
        }

        @Test
        @DisplayName("null and malformed URIs are handled without throwing")
        void nullAndMalformedUrisAreHandled() {
            assertNull(vault().retrieveSecret(null));
            assertDoesNotThrow(() -> vault().deleteSecret(null));
            assertDoesNotThrow(() -> vault().retrieveSecret("not-a-vault-uri"));
        }
    }

    @Nested
    @DisplayName("LocalSecretsVaultService (mock mode, non-durable)")
    class Local extends VaultContract {
        private final LocalSecretsVaultService delegate =
                new LocalSecretsVaultService("test-only-local-vault-encryption-key");

        @Override
        SecretsVaultService vault() {
            return delegate;
        }

        @Test
        @DisplayName("declares itself non-durable")
        void declaresItselfNonDurable() {
            // ConfigurationGuard depends on this to refuse a production boot.
            assertFalse(delegate.isDurable());
        }
    }

    @Nested
    @DisplayName("HashiCorpSecretsVaultService (durable, KV v2)")
    class HashiCorp extends VaultContract {
        private HashiCorpSecretsVaultService delegate;

        @Override
        SecretsVaultService vault() {
            assumeTrue(vaultIsReachable(), "No Vault reachable at " + VAULT_URI + "; skipping");
            if (delegate == null) {
                delegate = new HashiCorpSecretsVaultService(
                        new VaultTemplate(VaultEndpoint.from(URI.create(VAULT_URI)),
                                new TokenAuthentication(VAULT_TOKEN)),
                        "secret");
            }
            return delegate;
        }

        @Test
        @DisplayName("declares itself durable")
        void declaresItselfDurable() {
            assertTrue(((HashiCorpSecretsVaultService) vault()).isDurable());
        }

        @Test
        @DisplayName("records the mount in the URI so a mount change cannot silently redirect reads")
        void uriRecordsTheMount() {
            String uri = vault().storeSecret("symphony/agents/" + UUID.randomUUID() + "/token", "value");
            assertTrue(uri.startsWith("vault://secret/"), "Unexpected URI form: " + uri);
        }

        @Test
        @DisplayName("reports legacy in-memory URIs as unresolvable rather than guessing a mount")
        void legacyInMemoryUrisAreReportedUnresolvable() {
            HashiCorpSecretsVaultService service = (HashiCorpSecretsVaultService) vault();

            // LocalSecretsVaultService wrote "vault:/<path>" with no mount. Such
            // rows survive a switch to Vault while their secrets do not, and the
            // right answer is to say so, not to assume a mount.
            assertFalse(service.isResolvable("vault:/symphony/agents/abc/token"));
            assertFalse(service.isResolvable(null));
            assertFalse(service.isResolvable("vault://secret"));
            assertTrue(service.isResolvable("vault://secret/symphony/agents/abc/token"));
        }
    }

    private static boolean vaultIsReachable() {
        try {
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(800))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(VAULT_URI + "/v1/sys/health"))
                                    .timeout(Duration.ofMillis(800))
                                    .GET().build(),
                            HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
