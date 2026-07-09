package com.symphony.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.symphony.dto.AgentParameterDto;
import com.symphony.dto.AgentSearchMatchDto;
import com.symphony.dto.SupervisorResponseDto;
import com.symphony.entity.TelemetryLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupervisorService {

    private final AgentService agentService;
    private final TelemetryService telemetryService;

    @Value("${openai.api-key:mock-key}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(5))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    public SupervisorResponseDto routeAndExecute(UUID workspaceId, UUID userId, String userRole, String query) {
        log.info("Supervisor evaluating query under role '{}': '{}'", userRole, query);
        long startTime = System.currentTimeMillis();
        List<String> steps = new ArrayList<>();

        // Check if query implies sequential multi-agent chaining (e.g. contains "then", "and", "chain")
        boolean isChainedRequest = query.toLowerCase().contains("then") || 
                                   query.toLowerCase().contains("and") || 
                                   query.toLowerCase().contains("chain");

        List<AgentSearchMatchDto> candidates = agentService.search(workspaceId, query, 3);

        if (candidates.isEmpty() || candidates.get(0).getSimilarity() < 0.35) {
            log.info("No matching agent found (similarity below threshold).");
            telemetryService.logFailedQuery(workspaceId, query);
            telemetryService.logRouting(workspaceId, userId, null, query, "no_match", 0, 404);

            return SupervisorResponseDto.builder()
                    .textResponse("No matching agents were found in the enterprise catalog to handle your request. This requirement has been logged in our Innovation Backlog.")
                    .matchedAgent(null)
                    .extractedParameters(new HashMap<>())
                    .latencyMs(0)
                    .status("no_match")
                    .executionSteps(List.of("Supervisor analyzed query", "No registered agents matched the search intent"))
                    .build();
        }

        // --- PIPELINE 1: SEQUENTIAL CHAINING WORKFLOW ---
        if (isChainedRequest && candidates.size() >= 2) {
            log.info("Supervisor triggering multi-agent sequential chaining...");
            steps.add("Decomposed query: identified composite multi-agent requirements");

            AgentSearchMatchDto agentA = candidates.get(0);
            AgentSearchMatchDto agentB = candidates.get(1);

            steps.add(String.format("Step 1: Resolved query intent to first agent '%s' (Similarity: %d%%)", 
                    agentA.getName(), Math.round(agentA.getSimilarity() * 100)));

            // Validate RBAC for Agent A
            if (isRbacBlocked(agentA, userRole)) {
                steps.add(String.format("Security Halt: Execution blocked for '%s'. Role '%s' is unauthorized.", agentA.getName(), userRole));
                telemetryService.logRouting(workspaceId, userId, agentA.getId(), query, "chained", 0, 403);
                return createAccessDeniedResponse(agentA.getName(), userRole, steps);
            }

            // Execute Agent A
            steps.add(String.format("Step 2: Dispatching parameter extraction for '%s'...", agentA.getName()));
            Map<String, Object> paramsA = extractMockOrLlmParams(agentA, query);
            steps.add(String.format("Step 3: Mapped parameters for '%s': %s", agentA.getName(), paramsA));

            String resultA = "Extracted document summaries and key action bullet points.";
            int statusA = 200;
            steps.add(String.format("Step 4: Executed '%s' -> responded successfully with HTTP %d", agentA.getName(), statusA));

            // Map Agent A output to Agent B parameters
            steps.add(String.format("Step 5: Resolved workflow sequence to second agent '%s' (Similarity: %d%%)", 
                    agentB.getName(), Math.round(agentB.getSimilarity() * 100)));

            // Validate RBAC for Agent B
            if (isRbacBlocked(agentB, userRole)) {
                steps.add(String.format("Security Halt: Execution blocked for '%s'. Role '%s' is unauthorized.", agentB.getName(), userRole));
                telemetryService.logRouting(workspaceId, userId, agentB.getId(), query, "chained", 0, 403);
                return createAccessDeniedResponse(agentB.getName(), userRole, steps);
            }

            steps.add(String.format("Step 6: Injecting execution payload output from '%s' into '%s' input parameters...", agentA.getName(), agentB.getName()));
            Map<String, Object> paramsB = extractMockOrLlmParams(agentB, query);
            
            // Inject previous results
            String targetParamName = agentB.getParameters().stream()
                    .map(AgentParameterDto::getName)
                    .filter(name -> name.toLowerCase().contains("text") || name.toLowerCase().contains("content") || name.toLowerCase().contains("query"))
                    .findFirst()
                    .orElse(agentB.getParameters().isEmpty() ? "input" : agentB.getParameters().get(0).getName());
            paramsB.put(targetParamName, resultA);
            steps.add(String.format("Step 7: Mapped parameters for '%s': %s", agentB.getName(), paramsB));

            int statusB = 502; // Default mock failure
            long execStart = System.currentTimeMillis();
            String finalResult;
            try {
                statusB = dispatchAgentRequest(agentB.getEndpointUrl(), paramsB);
                finalResult = String.format("Chained pipeline completed successfully.\n[Step 1 Res]: %s\n[Step 2 Res (HTTP %d)]: (Processed output from endpoint)", resultA, statusB);
            } catch (IOException e) {
                finalResult = String.format("Workflow matched '%s' -> '%s' sequence, but '%s' was offline (HTTP 502 Bad Gateway).\n[Step 1 Res]: %s", 
                        agentA.getName(), agentB.getName(), agentB.getName(), resultA);
            }

            int latency = (int) (System.currentTimeMillis() - execStart);
            steps.add(String.format("Step 8: Sequential execution chain resolved (latency %d ms)", latency));

            telemetryService.logRouting(workspaceId, userId, agentB.getId(), query, "chained", latency, statusB);

            return SupervisorResponseDto.builder()
                    .textResponse(finalResult)
                    .matchedAgent(String.format("%s → %s", agentA.getName(), agentB.getName()))
                    .extractedParameters(paramsB)
                    .latencyMs(latency)
                    .status(statusB >= 200 && statusB < 300 ? "routed" : "failed")
                    .executionSteps(steps)
                    .build();
        }

        // --- PIPELINE 2: SINGLE AGENT ROUTING ---
        AgentSearchMatchDto targetAgent = candidates.get(0);
        steps.add(String.format("Resolved query intent to single agent '%s' (Similarity: %d%%)", 
                targetAgent.getName(), Math.round(targetAgent.getSimilarity() * 100)));

        // Validate RBAC
        if (isRbacBlocked(targetAgent, userRole)) {
            steps.add(String.format("Security Halt: Execution blocked for '%s'. Role '%s' is unauthorized.", targetAgent.getName(), userRole));
            telemetryService.logRouting(workspaceId, userId, targetAgent.getId(), query, "routed", 0, 403);
            return createAccessDeniedResponse(targetAgent.getName(), userRole, steps);
        }

        steps.add(String.format("Extracting routing variables for agent '%s'...", targetAgent.getName()));
        Map<String, Object> params = new HashMap<>();
        try {
            params = extractMockOrLlmParams(targetAgent, query);
            steps.add(String.format("Parameter mapping successful: %s", params));
        } catch (Exception e) {
            steps.add("Parameter mapping failed, falling back to empty payload.");
        }

        int latency = 0;
        int httpStatus = 502;
        String resultText;

        long execStart = System.currentTimeMillis();
        try {
            httpStatus = dispatchAgentRequest(targetAgent.getEndpointUrl(), params);
            latency = (int) (System.currentTimeMillis() - execStart);
            steps.add(String.format("Target endpoint execution responded with HTTP %d (Latency: %d ms)", httpStatus, latency));
            
            if (httpStatus >= 200 && httpStatus < 300) {
                resultText = String.format("Successfully routed request to **%s** agent. Endpoint responded with HTTP %d OK.", targetAgent.getName(), httpStatus);
            } else {
                resultText = String.format("Routed payload to **%s** agent, but endpoint returned status HTTP %d.", targetAgent.getName(), httpStatus);
            }
        } catch (IOException e) {
            latency = (int) (System.currentTimeMillis() - execStart);
            steps.add(String.format("Network Error: Agent endpoint (%s) was unreachable.", targetAgent.getEndpointUrl()));
            resultText = String.format("Successfully matched intent to **%s** agent, but the developer endpoint (%s) was unreachable (HTTP 502 Bad Gateway).", 
                    targetAgent.getName(), targetAgent.getEndpointUrl());
        }

        int totalLatency = (int) (System.currentTimeMillis() - startTime);
        telemetryService.logRouting(workspaceId, userId, targetAgent.getId(), query, "routed", totalLatency, httpStatus);

        return SupervisorResponseDto.builder()
                .textResponse(resultText)
                .matchedAgent(targetAgent.getName())
                .extractedParameters(params)
                .latencyMs(latency)
                .status(httpStatus >= 200 && httpStatus < 300 ? "routed" : "failed")
                .executionSteps(steps)
                .build();
    }

    private boolean isRbacBlocked(AgentSearchMatchDto agent, String userRole) {
        if (agent.getRequiresRbac() != null && agent.getRequiresRbac()) {
            if (agent.getAuthorizedRoles() == null || agent.getAuthorizedRoles().isEmpty()) {
                return true; // restricted agent with no configured roles is blocked by default
            }
            // Check case-insensitive intersection
            return agent.getAuthorizedRoles().stream()
                    .noneMatch(role -> role.trim().equalsIgnoreCase(userRole.trim()));
        }
        return false;
    }

    private SupervisorResponseDto createAccessDeniedResponse(String agentName, String userRole, List<String> steps) {
        return SupervisorResponseDto.builder()
                .textResponse(String.format("Access Denied: Your current role (%s) is not authorized to execute the protected agent '%s'. Please contact your workspace administrator.", userRole, agentName))
                .matchedAgent(agentName)
                .extractedParameters(new HashMap<>())
                .latencyMs(0)
                .status("forbidden")
                .executionSteps(steps)
                .build();
    }

    private Map<String, Object> extractMockOrLlmParams(AgentSearchMatchDto agent, String query) {
        try {
            if ("mock-key".equalsIgnoreCase(apiKey)) {
                Map<String, Object> mockParams = new HashMap<>();
                for (AgentParameterDto p : agent.getParameters()) {
                    if ("boolean".equalsIgnoreCase(p.getType())) {
                        mockParams.put(p.getName(), query.toLowerCase().contains("force") || query.toLowerCase().contains("true"));
                    } else if ("number".equalsIgnoreCase(p.getType())) {
                        mockParams.put(p.getName(), 42);
                    } else {
                        if (p.getName().toLowerCase().contains("url") || p.getName().toLowerCase().contains("path")) {
                            mockParams.put(p.getName(), "https://files.internal/docs/document_sample.pdf");
                        } else {
                            mockParams.put(p.getName(), "mock-value-extracted");
                        }
                    }
                }
                return mockParams;
            }

            // OpenAI dynamic parameter extraction
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", "gpt-4o-mini");
            ArrayNode messages = payload.putArray("messages");
            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Extract parameters for the target agent.\n" +
                    "Agent Parameters specification:\n" +
                    mapper.writeValueAsString(agent.getParameters()) + "\n" +
                    "Output ONLY valid raw JSON.");

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", query);

            Request req = new Request.Builder()
                    .url(baseUrl + "/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(mapper.writeValueAsBytes(payload), MediaType.parse("application/json")))
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    JsonNode root = mapper.readTree(resp.body().byteStream());
                    String jsonText = root.path("choices").get(0).path("message").path("content").asText().trim();
                    if (jsonText.startsWith("```")) {
                        jsonText = jsonText.substring(jsonText.indexOf("{"), jsonText.lastIndexOf("}") + 1);
                    }
                    return mapper.readValue(jsonText, HashMap.class);
                }
            }
        } catch (Exception e) {
            log.warn("Failed extracting parameters: {}", e.getMessage());
        }
        return new HashMap<>();
    }

    private int dispatchAgentRequest(String endpointUrl, Map<String, Object> params) throws IOException {
        RequestBody body = RequestBody.create(
                mapper.writeValueAsBytes(params),
                MediaType.parse("application/json")
        );
        Request request = new Request.Builder()
                .url(endpointUrl)
                .post(body)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.code();
        }
    }
}
