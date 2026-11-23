package com.symphony.config;

import com.symphony.config.jwt.InMemoryJwtKeyStore;
import com.symphony.config.jwt.JwtKeyProvider;
import com.symphony.config.jwt.JwtKeyStore;
import com.symphony.config.jwt.VaultJwtKeyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.core.VaultTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Chooses where JWT signing keys live.
 *
 * <p>Mirrors {@link VaultProviderConfig}: a durable, Vault-backed store in a
 * real deployment, and an ephemeral in-memory one locally so that no key
 * material has to be committed. The key store deliberately follows the vault
 * provider rather than mock mode — the same reasoning as for agent credentials,
 * since the question "where do secrets live" is independent of whether the LLM
 * is faked.
 */
@Configuration
@Slf4j
public class JwtKeyConfig {

    @Bean
    public JwtKeyStore jwtKeyStore(
            MockModeProperties mockMode,
            ObjectProvider<VaultTemplate> vaultTemplateProvider,
            @Value("${symphony.vault.provider:}") String configuredProvider,
            @Value("${symphony.vault.kv-mount:secret}") String kvMount,
            @Value("${symphony.jwt.vault-path:symphony/jwt/keyring}") String keyringPath) {

        String provider = VaultProviderConfig.resolveProvider(configuredProvider, mockMode.isEnabled());

        if (VaultProviderConfig.PROVIDER_HASHICORP.equals(provider)) {
            log.info("JWT signing keys: Vault-backed at {}/{}", kvMount, keyringPath);
            return new VaultJwtKeyStore(vaultTemplateProvider.getObject(), kvMount, keyringPath);
        }

        log.info("JWT signing keys: in-memory and ephemeral — a restart will invalidate issued tokens");
        return new InMemoryJwtKeyStore();
    }

    @Bean
    public JwtKeyProvider jwtKeyProvider(
            JwtKeyStore keyStore,
            @Value("${symphony.jwt.rotation-period-days:90}") long rotationPeriodDays,
            @Value("${symphony.jwt.rotation-overlap-hours:24}") long overlapHours) {

        return new JwtKeyProvider(
                keyStore,
                Duration.ofDays(rotationPeriodDays),
                Duration.ofHours(overlapHours),
                Clock.systemUTC());
    }
}
