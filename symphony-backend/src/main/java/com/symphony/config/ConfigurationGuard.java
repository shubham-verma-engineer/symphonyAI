package com.symphony.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
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
 * of one per restart.
 *
 * <p>It runs as a {@link BeanFactoryPostProcessor} — after configuration is
 * loaded, before any bean is created. It began as an {@code ApplicationRunner},
 * which runs after the context is up; once the Vault-backed beans arrived, an
 * unconfigured production boot died constructing the Vault client and the guard
 * never ran, so the operator learned about one missing setting per attempt.
 * Every invariant here is derivable from configuration alone, which is what lets
 * it run that early.
 *
 * <p>In mock mode every check is skipped and a banner is logged describing what
 * this particular instance is faking.
 */
@Component
@Slf4j
public class ConfigurationGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        boolean mockEnabled = Boolean.parseBoolean(property("symphony.mock.enabled", "false"));
        boolean llmMocked = MockModeProperties.resolveLlmMocked(property("symphony.mock.llm", ""), mockEnabled);
        String configuredProvider = property("symphony.vault.provider", "");

        if (mockEnabled) {
            logMockModeBanner(llmMocked, configuredProvider);
            return;
        }

        List<String> violations = findViolations(
                property("openai.api-key", ""),
                llmMocked,
                configuredProvider,
                property("symphony.vault.uri", ""),
                property("symphony.vault.token", ""));

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
     * <p>Takes its inputs as arguments rather than reading configuration so the
     * rules can be exercised directly, without standing up an application
     * context. Every message names the property or variable to change, because a
     * guard that blocks a deployment without saying why gets disabled rather than
     * fixed.
     *
     * <p>There is no longer a check for the literal key value {@code mock-key}:
     * since phase 6 of plan 0007 that value selects nothing, so it would simply
     * be sent to OpenAI and rejected — a loud failure, not the silent one the
     * check existed to catch. Nor is {@code symphony.vault.encryption-key}
     * checked: only the in-memory vault reads it, and that vault is refused here
     * on durability grounds.
     */
    static List<String> findViolations(String openAiApiKey, boolean llmMocked, String configuredProvider,
                                       String vaultUri, String vaultToken) {
        List<String> violations = new ArrayList<>();

        if (llmMocked) {
            violations.add("symphony.mock.llm is true, so embeddings, routing, parameter extraction and gap "
                    + "clustering would use deterministic local stand-ins. Semantic search would rank agents "
                    + "with vectors from a seeded PRNG while looking entirely normal. Unset SYMPHONY_MOCK_LLM.");
        } else if (isBlank(openAiApiKey)) {
            violations.add("openai.api-key is not set. Set OPENAI_API_KEY to a real key.");
        }

        String provider;
        try {
            provider = VaultProviderConfig.resolveProvider(configuredProvider, false);
        } catch (IllegalStateException e) {
            violations.add(e.getMessage());
            return violations;
        }

        if (VaultProviderConfig.PROVIDER_LOCAL.equals(provider)) {
            // One message, not two: the same setting fixes both, and listing
            // them separately reads as two problems to solve.
            violations.add("symphony.vault.provider is local, so neither agent credentials nor JWT signing "
                    + "keys survive a restart: credentials would vanish while the "
                    + "agents.credential_vault_uri rows pointing at them persist, and every restart would "
                    + "sign every user out. Set SYMPHONY_VAULT_PROVIDER=hashicorp.");
        } else {
            if (isBlank(vaultUri)) {
                violations.add("symphony.vault.uri is not set. Set VAULT_URI to the address of a reachable "
                        + "Vault, for example https://vault.internal:8200.");
            }
            if (isBlank(vaultToken)) {
                violations.add("symphony.vault.token is not set. Set VAULT_TOKEN to a token with read and "
                        + "write access to the configured KV mount.");
            }
        }

        return violations;
    }

    private String property(String key, String defaultValue) {
        String value = environment.getProperty(key);
        return value == null ? defaultValue : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Lists what this instance is actually faking. Mock mode no longer implies
     * every stand-in — the LLM, the vault and the key store can each be pointed
     * at the real thing — so a fixed list would tell a reader things that are
     * not true of the instance in front of them.
     */
    private void logMockModeBanner(boolean llmMocked, String configuredProvider) {
        boolean localVault;
        try {
            localVault = VaultProviderConfig.PROVIDER_LOCAL.equals(
                    VaultProviderConfig.resolveProvider(configuredProvider, true));
        } catch (IllegalStateException e) {
            // Reported properly when the vault provider bean is built.
            localVault = true;
        }

        StringBuilder banner = new StringBuilder("\n"
                + "***************************************************************\n"
                + "  MOCK MODE ENABLED - symphony.mock.enabled=true\n"
                + "***************************************************************\n"
                + "  This instance is NOT production-safe:\n");
        if (llmMocked) {
            banner.append("    - LLM routing and embeddings are deterministic local fakes\n");
        } else {
            banner.append("    - LLM calls are REAL (symphony.mock.llm=false) and will incur cost\n");
        }
        banner.append("    - Failed agent calls inside multi-agent chains are reported as successes\n")
                .append("    - Agent health probes are skipped unless symphony.heartbeat.assume-healthy=false\n");
        if (localVault) {
            banner.append("    - Agent credentials and JWT signing keys are lost on restart\n");
        }
        banner.append("    - Development accounts are seeded with a shared password\n")
                .append("  Unset SYMPHONY_MOCK_ENABLED for a real deployment.\n")
                .append("***************************************************************");
        log.warn(banner.toString());
    }
}
