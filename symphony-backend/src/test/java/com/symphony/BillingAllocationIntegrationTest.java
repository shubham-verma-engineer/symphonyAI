package com.symphony;

import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.AgentParameterDto;
import com.symphony.dto.EndpointsDto;
import com.symphony.dto.SecurityDto;
import com.symphony.entity.TelemetryLog;
import com.symphony.service.AgentService;
import com.symphony.service.SupervisorService;
import com.symphony.service.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class BillingAllocationIntegrationTest {

    @Autowired
    private SupervisorService supervisorService;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private AgentService agentService;

    private UUID wsId;
    private UUID userId;

    @BeforeEach
    public void setUp() {
        wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        userId = DatabaseInitializer.DEFAULT_USER_ID;

        // Register HR Auditor Agent
        agentService.onboardAgent(AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("billing-agent-1")
                .version("1.0.0")
                .description("Audits corporate payroll files.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder().name("text").type("string").required(true).build()))
                .security(SecurityDto.builder().requiresRBAC(false).build())
                .build(), wsId, userId);
    }

    @Test
    public void testDepartmentBillingAndDynamicCostSimulation() {
        // 1. Simulate Single Routed query for HR
        supervisorService.routeAndExecute(wsId, userId, "HR", "billing-agent-1 payroll query");

        // 2. Fetch raw audit logs
        List<TelemetryLog> logs = telemetryService.getRawTelemetryLogs(wsId);
        assertFalse(logs.isEmpty());

        // Find the log we just generated
        TelemetryLog hrLog = logs.stream()
                .filter(l -> "HR".equals(l.getDepartment()))
                .findFirst()
                .orElse(null);

        assertNotNull(hrLog);
        assertEquals("HR", hrLog.getDepartment());
        assertEquals("routed", hrLog.getRoutingDecision());
        // Verify dynamic cost for 'routed' is 0.00015
        assertEquals(0, hrLog.getTokenCost().compareTo(BigDecimal.valueOf(0.00015)));

        // 3. Fetch aggregated stats
        List<Map<String, Object>> stats = telemetryService.getDepartmentBillingStats(wsId);
        assertFalse(stats.isEmpty());

        Map<String, Object> hrStat = stats.stream()
                .filter(s -> "HR".equals(s.get("department")))
                .findFirst()
                .orElse(null);

        assertNotNull(hrStat);
        assertTrue(((Number) hrStat.get("countruns")).intValue() >= 1);
        assertTrue(new BigDecimal(hrStat.get("totalcost").toString()).compareTo(BigDecimal.ZERO) > 0);
    }
}
