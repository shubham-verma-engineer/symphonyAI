package com.symphony;

import com.sun.net.httpserver.HttpServer;
import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.AgentParameterDto;
import com.symphony.dto.EndpointsDto;
import com.symphony.dto.SecurityDto;
import com.symphony.entity.Agent;
import com.symphony.repository.AgentRepository;
import com.symphony.service.AgentHeartbeatService;
import com.symphony.service.AgentService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the heartbeat checker against endpoints that really answer.
 *
 * <p>Stands up a local HTTP server with a healthy path and a failing one, so
 * each status transition follows from an actual probe. Before phase 5 of plan
 * 0007 the checker skipped any URL containing {@code localhost}, {@code mock} or
 * {@code 127.0.0.1} and marked those agents {@code ACTIVE} unprobed — so this
 * test's "local agent is ACTIVE" assertion passed because of the bypass, not
 * because anything had been contacted. That bypass also meant an agent behind a
 * sidecar, the normal arrangement in the deployments this platform targets, was
 * never actually checked.
 *
 * <p>{@code symphony.heartbeat.assume-healthy=false} opts into real probing
 * while the rest of the suite stays offline.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "symphony.heartbeat.assume-healthy=false")
public class AgentHeartbeatIntegrationTest {

    private static HttpServer server;
    private static String baseUrl;

    @Autowired
    private AgentHeartbeatService agentHeartbeatService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private AgentRepository agentRepository;

    private UUID wsId;
    private UUID userId;

    @BeforeAll
    static void startStubAgent() throws IOException {
        // Port 0: the OS picks a free one, so the test cannot collide with a
        // running application or another test run.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthy", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStubAgent() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    public void setUp() {
        wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        userId = DatabaseInitializer.DEFAULT_USER_ID;
    }

    private Agent onboard(String name, String healthPath) {
        return agentService.onboardAgent(AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name(name)
                .version("1.0.0")
                .description("Agent used to verify heartbeat probing behaviour.")
                .endpoints(EndpointsDto.builder()
                        .execution(baseUrl + "/run")
                        .health(baseUrl + healthPath)
                        .build())
                .parameters(List.of(AgentParameterDto.builder()
                        .name("text").type("string").required(true).build()))
                .security(SecurityDto.builder().requiresRBAC(false).build())
                .build(), wsId, userId);
    }

    @Test
    public void anAgentAnsweringItsHealthEndpointBecomesActive() {
        Agent agent = onboard("healthy-heartbeat-agent", "/healthy");
        agent.setStatus("PENDING");
        agentRepository.save(agent);

        agentHeartbeatService.runHeartbeatCheck();

        assertEquals("ACTIVE", agentRepository.findById(agent.getId()).orElseThrow().getStatus(),
                "A localhost endpoint returning 200 must be probed and marked ACTIVE");
    }

    @Test
    public void anAgentReturningAnErrorStatusBecomesUnhealthy() {
        Agent agent = onboard("erroring-heartbeat-agent", "/broken");
        agent.setStatus("ACTIVE");
        agentRepository.save(agent);

        agentHeartbeatService.runHeartbeatCheck();

        assertEquals("UNHEALTHY", agentRepository.findById(agent.getId()).orElseThrow().getStatus(),
                "A 503 from the health endpoint must mark the agent UNHEALTHY");
    }

    @Test
    public void anUnreachableAgentBecomesUnhealthy() {
        Agent agent = onboard("unreachable-heartbeat-agent", "/healthy");
        agent.setStatus("ACTIVE");
        agent.setHealthCheckUrl("http://unreachable-domain-12345.invalid/health");
        agentRepository.save(agent);

        agentHeartbeatService.runHeartbeatCheck();

        assertEquals("UNHEALTHY", agentRepository.findById(agent.getId()).orElseThrow().getStatus());
    }

    @Test
    public void aLocalhostAgentIsNoLongerAssumedHealthy() {
        // The regression this guards: the old substring bypass marked anything
        // on localhost ACTIVE without a request, which in a sidecar deployment
        // meant real agents were never checked at all.
        Agent agent = onboard("localhost-bypass-agent", "/broken");
        agent.setStatus("ACTIVE");
        agentRepository.save(agent);

        agentHeartbeatService.runHeartbeatCheck();

        assertNotEquals("ACTIVE", agentRepository.findById(agent.getId()).orElseThrow().getStatus(),
                "A failing localhost endpoint must not be assumed healthy");
    }

    @Test
    public void anAgentRecoversFromUnhealthyBackToActive() {
        Agent agent = onboard("recovering-heartbeat-agent", "/healthy");
        agent.setStatus("UNHEALTHY");
        agentRepository.save(agent);

        agentHeartbeatService.runHeartbeatCheck();

        assertEquals("ACTIVE", agentRepository.findById(agent.getId()).orElseThrow().getStatus(),
                "An agent whose endpoint answers again must return to ACTIVE");
    }

    @Test
    public void agentsWithoutAHealthEndpointAreLeftAlone() {
        Agent agent = onboard("no-health-url-agent", "/healthy");
        agent.setStatus("PENDING");
        agent.setHealthCheckUrl(null);
        agentRepository.save(agent);

        agentHeartbeatService.runHeartbeatCheck();

        assertEquals("PENDING", agentRepository.findById(agent.getId()).orElseThrow().getStatus(),
                "An agent that declared no health endpoint must not be judged by one");
    }
}
