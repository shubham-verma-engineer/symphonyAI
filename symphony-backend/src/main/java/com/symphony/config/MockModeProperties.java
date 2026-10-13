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
}
