package com.symphony;

import com.symphony.config.DatabaseInitializer;
import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.AgentParameterDto;
import com.symphony.dto.EndpointsDto;
import com.symphony.dto.SecurityDto;
import com.symphony.dto.SupervisorResponseDto;
import com.symphony.entity.Agent;
import com.symphony.repository.AgentRepository;
import com.symphony.service.AgentService;
import com.symphony.service.PiiFirewallService;
import com.symphony.service.SecretsVaultService;
import com.symphony.service.SupervisorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class GovernanceOrchestrationIntegrationTest {

    @Autowired
    private PiiFirewallService firewallService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private SupervisorService supervisorService;

    @Autowired
    private SecretsVaultService secretsVaultService;

    @Autowired
    private AgentRepository agentRepository;

    @Test
    public void testPiiFirewallMaskingAndDetokenizing() {
        String original = "Hello admin, review this credit card 4111-2222-3333-4444 and reply to tester@symphony.com please.";
        
        PiiFirewallService.FirewallResult result = firewallService.sanitize(original);
        assertTrue(result.isSanitized());
        assertTrue(result.getMaskedText().contains("[MASKED_CARD_1]"));
        assertTrue(result.getMaskedText().contains("[MASKED_EMAIL_1]"));
        assertEquals("4111-2222-3333-4444", result.getTokenMap().get("[MASKED_CARD_1]"));
        assertEquals("tester@symphony.com", result.getTokenMap().get("[MASKED_EMAIL_1]"));

        String restored = firewallService.detokenize(result.getMaskedText(), result.getTokenMap());
        assertEquals(original, restored);
    }

    @Test
    public void testRbacAccessControlBlocks() {
        UUID wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = DatabaseInitializer.DEFAULT_USER_ID;

        // Register HR-locked agent
        AgentManifestDto manifest = AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("payroll-agent")
                .version("1.0.0")
                .description("Handles internal payroll processing for HR department.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder()
                        .name("departmentCode")
                        .type("string")
                        .description("Code")
                        .required(true)
                        .build()))
                .security(SecurityDto.builder()
                        .requiresRBAC(true)
                        .authorizedRoles(List.of("HR", "Admin"))
                        .build())
                .build();

        assertNotNull(agentService.onboardAgent(manifest, wsId, userId));

        // Case 1: Unauthorized Role -> Expected Block
        SupervisorResponseDto blockResponse = supervisorService.routeAndExecute(
                wsId, userId, List.of("Employee"), "Employee", "Help me with payroll checks please"
        );
        assertEquals("forbidden", blockResponse.getStatus());
        assertTrue(blockResponse.getTextResponse().contains("Access Denied"));

        // Case 2: Authorized Role -> Expected Pass (will try to execute and fail on socket connection, but not forbidden!)
        SupervisorResponseDto passResponse = supervisorService.routeAndExecute(
                wsId, userId, List.of("HR"), "HR", "Help me with payroll checks please"
        );
        assertNotEquals("forbidden", passResponse.getStatus());
        assertEquals("payroll-agent", passResponse.getMatchedAgent());
    }

    @Test
    public void testMultiAgentSequentialChainingPlan() {
        UUID wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = DatabaseInitializer.DEFAULT_USER_ID;

        // Register Summarizer Agent (matches "summar")
        agentService.onboardAgent(AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("summarizer-agent")
                .version("1.0.0")
                .description("Extracts details and summarises key text.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder().name("text").type("string").required(true).build()))
                .security(SecurityDto.builder().requiresRBAC(false).build())
                .build(), wsId, userId);

        // Register Translator Agent (matches "transla")
        agentService.onboardAgent(AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("translator-agent")
                .version("1.0.0")
                .description("Translates English text into French.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder().name("content").type("string").required(true).build()))
                .security(SecurityDto.builder().requiresRBAC(false).build())
                .build(), wsId, userId);

        // Run compound chaining query
        SupervisorResponseDto chainResponse = supervisorService.routeAndExecute(
                wsId, userId, List.of("Employee"), "Employee", "summarize document details and then translate them to French"
        );

        // Assert workflow pipeline
        assertTrue(chainResponse.getMatchedAgent().contains("summarizer-agent → translator-agent"));
        assertNotNull(chainResponse.getExecutionSteps());
        assertTrue(chainResponse.getExecutionSteps().size() > 5);
        assertTrue(chainResponse.getExecutionSteps().stream().anyMatch(step -> step.contains("identified composite")));
    }

    @Test
    public void testSecretsVaultIntegration() {
        UUID wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = DatabaseInitializer.DEFAULT_USER_ID;

        // Onboard agent with a secretKey
        AgentManifestDto manifest = AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("secured-agent")
                .version("1.0.0")
                .description("A test agent that requires dynamic api authentication token.")
                .endpoints(EndpointsDto.builder()
                        .execution("http://localhost:8091/actuator/health")
                        .health("http://localhost:8091/actuator/health")
                        .build())
                .parameters(List.of(AgentParameterDto.builder().name("input").type("string").required(true).build()))
                .security(SecurityDto.builder()
                        .requiresRBAC(false)
                        .secretKey("my-super-secret-passphrase-abc")
                        .build())
                .build();

        Agent onboarded = agentService.onboardAgent(manifest, wsId, userId);
        assertNotNull(onboarded);

        // 1. Verify credential_vault_uri is set in database and plaintext is not leaked
        assertNotNull(onboarded.getCredentialVaultUri());
        assertTrue(onboarded.getCredentialVaultUri().startsWith("vault:/"));

        Agent dbAgent = agentRepository.findById(onboarded.getId()).orElse(null);
        assertNotNull(dbAgent);
        assertEquals(onboarded.getCredentialVaultUri(), dbAgent.getCredentialVaultUri());

        // 2. Verify vault decrypts the secret correctly
        String retrievedSecret = secretsVaultService.retrieveSecret(dbAgent.getCredentialVaultUri());
        assertEquals("my-super-secret-passphrase-abc", retrievedSecret);

        // 3. Verify execution routing works and triggers dispatch
        SupervisorResponseDto response = supervisorService.routeAndExecute(
                wsId, userId, List.of("Employee"), "Employee", "execute test agent query"
        );
        assertEquals("secured-agent", response.getMatchedAgent());
    }
}
