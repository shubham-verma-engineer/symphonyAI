package com.symphony;

import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.AgentParameterDto;
import com.symphony.dto.EndpointsDto;
import com.symphony.dto.SecurityDto;
import com.symphony.entity.Agent;
import com.symphony.repository.AgentRepository;
import com.symphony.service.AgentService;
import com.symphony.service.AgentHeartbeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class AgentHeartbeatIntegrationTest {

    @Autowired
    private AgentHeartbeatService agentHeartbeatService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentRepository agentRepository;

    private UUID wsId;
    private UUID userId;

    @BeforeEach
    public void setUp() {
        wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        userId = DatabaseInitializer.DEFAULT_USER_ID;
    }

    @Test
    public void testHeartbeatCheckerLocalAndUnreachableEndpoints() {
        // 1. Onboard a simulated local mock agent (should resolve to ACTIVE automatically)
        Agent localAgent = agentService.onboardAgent(AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("local-heartbeat-agent")
                .version("1.0.0")
                .description("Simulates local heartbeat checking.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder().name("text").type("string").required(true).build()))
                .security(SecurityDto.builder().requiresRBAC(false).build())
                .build(), wsId, userId);

        // 2. Onboard an unreachable external agent (onboard with mock first, then set to unreachable)
        Agent remoteAgent = agentService.onboardAgent(AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("remote-heartbeat-agent")
                .version("1.0.0")
                .description("Simulates unreachable external agent.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder().name("text").type("string").required(true).build()))
                .security(SecurityDto.builder().requiresRBAC(false).build())
                .build(), wsId, userId);

        // Set status to pending first and update remote agent to unreachable URL
        localAgent.setStatus("PENDING");
        agentRepository.save(localAgent);

        remoteAgent.setStatus("PENDING");
        remoteAgent.setHealthCheckUrl("http://unreachable-domain-12345.com/health");
        agentRepository.save(remoteAgent);

        // Run Heartbeat Checker
        agentHeartbeatService.runHeartbeatCheck();

        // Assert local agent is now ACTIVE
        Agent verifiedLocal = agentRepository.findById(localAgent.getId()).orElseThrow();
        assertEquals("ACTIVE", verifiedLocal.getStatus());

        // Assert remote agent went UNHEALTHY
        Agent verifiedRemote = agentRepository.findById(remoteAgent.getId()).orElseThrow();
        assertEquals("UNHEALTHY", verifiedRemote.getStatus());
    }
}
