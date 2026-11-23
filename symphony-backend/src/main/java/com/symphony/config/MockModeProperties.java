package com.symphony.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for whether the platform is running in local mock mode.
 *
 * <p>Before this existed, each development shortcut was reached by inference: a
 * missing {@code OPENAI_API_KEY} switched the router and the embedding client to
 * seeded fakes, and a health URL containing {@code localhost} suppressed the
 * health probe entirely. Both inferences are silently wrong in a real
 * deployment — agents legitimately live on localhost behind a sidecar, and a
 * forgotten environment variable is not a request for fabricated search
 * rankings.
 *
 * <p>Mock mode is now explicit and defaults to {@code false}, so production
 * behaviour is what you get unless someone asks for otherwise.
 */
@Component
@ConfigurationProperties(prefix = "symphony.mock")
@Getter
@Setter
public class MockModeProperties {

    /**
     * When true, the platform substitutes deterministic offline behaviour for
     * every outbound dependency: seeded embeddings, canned parameter
     * extraction, bypassed agent calls, skipped health probes, and seeded
     * development user accounts.
     */
    private boolean enabled = false;

    /**
     * Whether LLM-backed behaviour — embeddings, parameter extraction, DAG
     * planning, gap clustering — uses deterministic local stand-ins instead of
     * the OpenAI API. Unset means "follow {@link #enabled}".
     *
     * <p>Separate because the questions are separate: running the rest of the
     * platform offline (seeded accounts, no health probes, no agent calls) is
     * often wanted together with a <em>real</em> LLM, for example when tuning
     * routing quality locally. Before this property existed the same effect
     * came from leaving mock mode's key-sniffing in place and supplying a real
     * {@code OPENAI_API_KEY}; removing the sniffing would otherwise have
     * removed that option. {@code ConfigurationGuard} refuses a production boot
     * with a mocked LLM.
     */
    private Boolean llm;

    /** {@link #llm} if set, otherwise {@link #enabled}. */
    public boolean isLlmMocked() {
        return llm != null ? llm : enabled;
    }

    /**
     * The same rule as {@link #isLlmMocked()}, from raw configuration text.
     *
     * <p>{@code ConfigurationGuard} runs before beans exist, so it cannot ask a
     * bound instance of this class. Sharing the rule keeps the guard and the
     * services from ever disagreeing about whether the LLM is mocked.
     */
    public static boolean resolveLlmMocked(String llmSetting, boolean mockEnabled) {
        if (llmSetting == null || llmSetting.isBlank()) {
            return mockEnabled;
        }
        return Boolean.parseBoolean(llmSetting.trim());
    }
}
