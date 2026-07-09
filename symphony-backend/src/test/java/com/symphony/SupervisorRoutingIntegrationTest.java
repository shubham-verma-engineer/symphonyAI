package com.symphony;

import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.AgentParameterDto;
import com.symphony.dto.EndpointsDto;
import com.symphony.dto.SecurityDto;
import com.symphony.dto.SupervisorResponseDto;
import com.symphony.entity.InnovationGap;
import com.symphony.repository.FailedQueryRepository;
import com.symphony.repository.InnovationGapRepository;
import com.symphony.service.AgentService;
import com.symphony.service.GapClusteringService;
import com.symphony.service.SupervisorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class SupervisorRoutingIntegrationTest {

    @Autowired
    private AgentService agentService;

    @Autowired
    private SupervisorService supervisorService;

    @Autowired
    private FailedQueryRepository failedQueryRepository;

    @Autowired
    private GapClusteringService clusteringService;

    @Autowired
    private InnovationGapRepository gapRepository;

    @Test
    public void testCompleteSupervisorAndGapDetectionFlow() {
        UUID wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = DatabaseInitializer.DEFAULT_USER_ID;

        // 1. Onboard a dummy document summary agent (health ping will mock-fail unless handled, but since we run test we can use a mock endpoint or catch the error)
        // To prevent loopback ping failure in tests, we onboard using mock urls that might fail health check,
        // so let's use a URL we know will respond (like localhost:8091/actuator/health) which is active during boot!
        AgentManifestDto manifest = AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("test-summarizer")
                .version("1.0.0")
                .description("A summarizer agent for documents.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder()
                        .name("documentUrl")
                        .type("string")
                        .description("URL path")
                        .required(true)
                        .build()))
                .security(SecurityDto.builder().requiresRBAC(false).build())
                .build();

        // Onboard agent (will ping health check endpoint successfully)
        assertNotNull(agentService.onboardAgent(manifest, wsId, userId));

        // 2. Query the supervisor with a matching request
        SupervisorResponseDto matchResp = supervisorService.routeAndExecute(wsId, userId, "Please run summary over document");
        assertEquals("failed", matchResp.getStatus());
        assertEquals("test-summarizer", matchResp.getMatchedAgent());
        assertTrue(matchResp.getExtractedParameters().containsKey("documentUrl"));

        // 3. Query the supervisor with an UNMATCHED request
        SupervisorResponseDto noMatchResp = supervisorService.routeAndExecute(wsId, userId, "I need to fly on an airplane");
        assertEquals("no_match", noMatchResp.getStatus());
        assertNull(noMatchResp.getMatchedAgent());

        // Verify that failed query is logged
        assertFalse(failedQueryRepository.findAllByWorkspaceIdAndIsClusteredFalse(wsId).isEmpty(), "Failed query should be logged");

        // 4. Trigger gap clustering pipeline
        clusteringService.runClusteringPipeline(wsId);

        // Verify that innovation gap was generated
        List<InnovationGap> gaps = gapRepository.findAllByWorkspaceIdOrderByCountFrequencyDesc(wsId);
        assertFalse(gaps.isEmpty(), "Innovation gap leaderboard should contain clustered gaps");
        assertTrue(gaps.stream().anyMatch(g -> g.getTitle().toLowerCase().contains("general")), "Leaderboard should cluster failed queries");
    }
}
