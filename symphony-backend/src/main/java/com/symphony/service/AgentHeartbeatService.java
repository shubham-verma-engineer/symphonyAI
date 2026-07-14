package com.symphony.service;

import com.symphony.entity.Agent;
import com.symphony.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentHeartbeatService {

    private final AgentRepository agentRepository;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(3))
            .build();

    @Scheduled(fixedRateString = "${symphony.heartbeat.interval-ms:30000}")
    public void runHeartbeatCheck() {
        log.info("Running automatic scheduled heartbeat checks for all agents...");
        List<Agent> agents = agentRepository.findAll();
        for (Agent agent : agents) {
            String healthUrl = agent.getHealthCheckUrl();
            if (healthUrl == null || healthUrl.trim().isEmpty()) {
                continue;
            }

            // In local/mock mode, we can skip or simulate success for localhost/mock endpoints
            if (healthUrl.toLowerCase().contains("mock") || healthUrl.toLowerCase().contains("localhost") || healthUrl.toLowerCase().contains("127.0.0.1")) {
                if (!"ACTIVE".equalsIgnoreCase(agent.getStatus())) {
                    agent.setStatus("ACTIVE");
                    agentRepository.save(agent);
                    log.info("Heartbeat: simulated success for mock/local agent '{}' -> status set to ACTIVE", agent.getName());
                }
                continue;
            }

            log.debug("Heartbeat: checking health of agent '{}' at '{}'", agent.getName(), healthUrl);
            Request request = new Request.Builder()
                    .url(healthUrl)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    if (!"ACTIVE".equalsIgnoreCase(agent.getStatus())) {
                        agent.setStatus("ACTIVE");
                        agentRepository.save(agent);
                        log.info("Heartbeat: agent '{}' is now ACTIVE (status code {})", agent.getName(), response.code());
                    }
                } else {
                    if (!"UNHEALTHY".equalsIgnoreCase(agent.getStatus())) {
                        agent.setStatus("UNHEALTHY");
                        agentRepository.save(agent);
                        log.warn("Heartbeat: agent '{}' went UNHEALTHY (status code {})", agent.getName(), response.code());
                    }
                }
            } catch (IOException e) {
                if (!"UNHEALTHY".equalsIgnoreCase(agent.getStatus())) {
                    agent.setStatus("UNHEALTHY");
                    agentRepository.save(agent);
                    log.warn("Heartbeat: agent '{}' went UNHEALTHY (endpoint unreachable: {})", agent.getName(), e.getMessage());
                }
            }
        }
    }
}
