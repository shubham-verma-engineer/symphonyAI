package com.symphony.service;

import com.symphony.config.MockModeProperties;
import com.symphony.entity.Agent;
import com.symphony.repository.AgentRepository;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Periodically probes each registered agent's health endpoint and keeps
 * {@code agents.status} in step with what the probe found.
 */
@Service
@Slf4j
public class AgentHeartbeatService {

    private static final String ACTIVE = "ACTIVE";
    private static final String UNHEALTHY = "UNHEALTHY";

    private final AgentRepository agentRepository;
    private final MockModeProperties mockMode;

    /**
     * Whether to assume agents are healthy instead of probing them.
     *
     * <p>Separate from mock mode, which concerns whether the LLM is faked, not
     * whether agents are reachable. Defaulting it to mock mode keeps local
     * development usable — agents registered with placeholder URLs would
     * otherwise all report UNHEALTHY — while leaving the real probe path
     * reachable for anyone who wants to exercise it, tests included.
     */
    private final boolean assumeHealthyOverride;
    private final boolean hasOverride;

    public AgentHeartbeatService(AgentRepository agentRepository, MockModeProperties mockMode,
                                 @Value("${symphony.heartbeat.assume-healthy:}") String assumeHealthy) {
        this.agentRepository = agentRepository;
        this.mockMode = mockMode;
        this.hasOverride = assumeHealthy != null && !assumeHealthy.isBlank();
        this.assumeHealthyOverride = this.hasOverride && Boolean.parseBoolean(assumeHealthy.trim());
    }

    private boolean assumeHealthy() {
        return hasOverride ? assumeHealthyOverride : mockMode.isEnabled();
    }

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Runs every {@code symphony.heartbeat.interval-ms} (default 30s).
     *
     * <p>Logs at DEBUG and returns early when there is nothing to check. At
     * INFO this emitted a line every 30 seconds whether or not any agent was
     * registered — 120 lines an hour saying nothing, which buried the lines
     * that did mean something and made a healthy idle instance look stuck.
     *
     * <p>Note: with more than one instance deployed, every replica runs this
     * schedule, so agents are probed once per replica per interval and the
     * status writes contend. A distributed lock is needed before scaling out;
     * see the deferred list in plan 0007.
     */
    @Scheduled(fixedRateString = "${symphony.heartbeat.interval-ms:30000}")
    public void runHeartbeatCheck() {
        List<Agent> agents = agentRepository.findAll().stream()
                .filter(a -> a.getHealthCheckUrl() != null && !a.getHealthCheckUrl().trim().isEmpty())
                .toList();

        if (agents.isEmpty()) {
            log.debug("Heartbeat: no agents with a health endpoint to check.");
            return;
        }

        if (assumeHealthy()) {
            // Nothing is probed: agents registered with placeholder endpoints
            // would otherwise all report UNHEALTHY and make the local registry
            // look broken.
            int promoted = 0;
            for (Agent agent : agents) {
                if (!ACTIVE.equalsIgnoreCase(agent.getStatus())) {
                    agent.setStatus(ACTIVE);
                    agentRepository.save(agent);
                    promoted++;
                }
            }
            log.debug("Heartbeat: assumed {} agent(s) healthy without probing, {} status change(s).",
                    agents.size(), promoted);
            return;
        }

        int healthy = 0;
        int unhealthy = 0;
        for (Agent agent : agents) {
            if (probeIsHealthy(agent)) {
                healthy++;
                markStatus(agent, ACTIVE, null);
            } else {
                unhealthy++;
            }
        }

        // One summary line per sweep rather than one per agent. Transitions are
        // logged individually by markStatus, because those are the events worth
        // reading.
        log.debug("Heartbeat: probed {} agent(s) - {} healthy, {} unhealthy.", agents.size(), healthy, unhealthy);
    }

    /**
     * Probes one agent. Any non-success response or transport failure is
     * unhealthy; the reason is recorded on the transition, not on every sweep.
     *
     * <p>No longer skips URLs containing {@code localhost} or {@code mock}. That
     * shortcut meant an agent behind a sidecar — the normal arrangement in the
     * deployments this platform targets — was reported {@code ACTIVE} without
     * ever being contacted, which made this whole service decorative.
     */
    private boolean probeIsHealthy(Agent agent) {
        Request request = new Request.Builder()
                .url(agent.getHealthCheckUrl())
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return true;
            }
            markStatus(agent, UNHEALTHY, "health endpoint returned HTTP " + response.code());
            return false;
        } catch (IOException e) {
            markStatus(agent, UNHEALTHY, "health endpoint unreachable: " + e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            // A malformed health URL in the manifest: a permanent condition, so
            // it is reported like any other unhealthy state rather than thrown,
            // which would abandon the rest of the sweep.
            markStatus(agent, UNHEALTHY, "health endpoint URL is not valid: " + e.getMessage());
            return false;
        }
    }

    /** Writes a status only when it changes, and logs the transition with its cause. */
    private void markStatus(Agent agent, String status, String reason) {
        if (status.equalsIgnoreCase(agent.getStatus())) {
            return;
        }
        agent.setStatus(status);
        agentRepository.save(agent);

        if (UNHEALTHY.equals(status)) {
            log.warn("Heartbeat: agent '{}' is now UNHEALTHY ({})", agent.getName(), reason);
        } else {
            log.info("Heartbeat: agent '{}' recovered and is now ACTIVE", agent.getName());
        }
    }
}
