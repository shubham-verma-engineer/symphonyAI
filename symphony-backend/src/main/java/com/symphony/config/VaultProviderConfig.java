package com.symphony.config;

import com.symphony.service.HashiCorpSecretsVaultService;
import com.symphony.service.LocalSecretsVaultService;
import com.symphony.service.SecretsVaultService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

import java.net.URI;

/**
 * Chooses which {@link SecretsVaultService} backs the application.
 *
 * <p>Expressed as a single explicit decision rather than as
 * {@code @ConditionalOnProperty} on each implementation, for two reasons. The
 * conditional form spread one choice across two files, where neither stated the
 * alternative — and it keyed the choice off {@code symphony.mock.enabled},
 * conflating two unrelated questions: whether the LLM is faked, and where
 * secrets live. That made the one combination needed to test credential
 * durability locally — faked LLM, real Vault — impossible to express.
 *
 * <p>{@code symphony.vault.provider} now selects the implementation, defaulting
 * to {@code local} in mock mode and {@code hashicorp} otherwise, so behaviour is
 * unchanged for anyone who does not set it. {@code ConfigurationGuard} still
 * refuses to start a production instance on a non-durable vault, so this
 * property cannot be used to sneak the in-memory store into production.
 */
@Configuration
@Slf4j
public class VaultProviderConfig {

    static final String PROVIDER_LOCAL = "local";
    static final String PROVIDER_HASHICORP = "hashicorp";

    @Bean
    public SecretsVaultService secretsVaultService(
            MockModeProperties mockMode,
            @Value("${symphony.vault.provider:}") String configuredProvider,
            @Value("${symphony.vault.uri:}") String vaultUri,
            @Value("${symphony.vault.token:}") String vaultToken,
            @Value("${symphony.vault.kv-mount:secret}") String kvMount,
            @Value("${symphony.vault.encryption-key:}") String encryptionKey) {

        String provider = resolveProvider(configuredProvider, mockMode.isEnabled());

        if (PROVIDER_LOCAL.equals(provider)) {
            log.info("Secrets vault provider: local (in-memory, non-durable)");
            return new LocalSecretsVaultService(encryptionKey);
        }

        log.info("Secrets vault provider: hashicorp (durable, KV v2 mount '{}')", kvMount);
        return new HashiCorpSecretsVaultService(vaultTemplate(vaultUri, vaultToken), kvMount);
    }

    /**
     * An explicit setting wins; otherwise mock mode decides. An unrecognised
     * value fails fast rather than falling back, because silently defaulting a
     * misspelled {@code hashicorp} to the in-memory store is exactly the class
     * of quiet misconfiguration plan 0007 exists to eliminate.
     */
    static String resolveProvider(String configuredProvider, boolean mockEnabled) {
        if (configuredProvider != null && !configuredProvider.isBlank()) {
            String normalised = configuredProvider.trim().toLowerCase();
            if (!PROVIDER_LOCAL.equals(normalised) && !PROVIDER_HASHICORP.equals(normalised)) {
                throw new IllegalStateException(String.format(
                        "symphony.vault.provider is '%s'; expected '%s' or '%s'.",
                        configuredProvider, PROVIDER_LOCAL, PROVIDER_HASHICORP));
            }
            return normalised;
        }
        return mockEnabled ? PROVIDER_LOCAL : PROVIDER_HASHICORP;
    }

    private VaultTemplate vaultTemplate(String vaultUri, String vaultToken) {
        if (vaultUri == null || vaultUri.isBlank()) {
            throw new IllegalStateException(
                    "symphony.vault.uri is not set. Set VAULT_URI to a reachable Vault "
                    + "(for example http://localhost:8200), or set symphony.vault.provider=local "
                    + "to use the in-memory store for local development.");
        }
        if (vaultToken == null || vaultToken.isBlank()) {
            throw new IllegalStateException(
                    "symphony.vault.token is not set. Set VAULT_TOKEN to a token with read and write "
                    + "access to the configured KV mount.");
        }

        // The address is logged; the token is not, and must never be.
        log.info("Configuring Vault client against {}", vaultUri);
        return new VaultTemplate(VaultEndpoint.from(URI.create(vaultUri)), new TokenAuthentication(vaultToken));
    }
}
