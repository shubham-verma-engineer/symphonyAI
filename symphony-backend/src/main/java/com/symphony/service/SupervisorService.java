package com.symphony.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public SupervisorResponseDto routeAndExecute(UUID workspaceId, UUID userId, String query) {
        log.info("Supervisor evaluating query: '{}'", query);
        long startTime = System.currentTimeMillis();

        // 1. Semantic search for matching agents (retrieve top 3)
        List<AgentSearchMatchDto> matches = agentService.search(workspaceId, query, 3);

        // 2. Evaluate if a suitable match exists (similarity threshold 0.35)
        if (matches.isEmpty() || matches.get(0).getSimilarity() < 0.35) {
            log.info("No matching agent found (top score below threshold). Routing to NO_MATCH.");
            
            // Log failed query for gap analytics
            telemetryService.logFailedQuery(workspaceId, query);
            telemetryService.logRouting(workspaceId, userId, null, query, "no_match", 0, 404);

            return SupervisorResponseDto.builder()
                    .textResponse("No matching agents were found in the enterprise catalog to handle your request. This requirement has been logged in our Innovation Backlog to guide future development.")
                    .matchedAgent(null)
                    .extractedParameters(new HashMap<>())
                    .latencyMs(0)
                    .status("no_match")
                    .build();
        }

        // 3. Select top match candidate
        AgentSearchMatchDto targetAgent = matches.get(0);
        log.info("Supervisor matched query to agent: '{}' (Similarity: {}%)", targetAgent.getName(), Math.round(targetAgent.getSimilarity() * 100));

        // 4. Extract parameters (Mock or LLM-based)
        Map<String, Object> params = new HashMap<>();
        try {
            params = extractParameters(targetAgent, query);
        } catch (Exception e) {
            log.warn("Parameter extraction failed: {}", e.getMessage());
            // Fallback to empty parameters
        }

        // 5. Execute Loopback outbound POST request to target agent
        int latency = 0;
        int httpStatus = 502; // Default to Bad Gateway if connection fails
        String executionResultText;

        long execStart = System.currentTimeMillis();
        try {
            httpStatus = dispatchAgentRequest(targetAgent.getEndpointUrl(), params);
            latency = (int) (System.currentTimeMillis() - execStart);
            
            if (httpStatus >= 200 && httpStatus < 300) {
                executionResultText = String.format("Successfully routed your request to the **%s** agent. The target endpoint responded with HTTP %d OK. Extracted Parameters: %s", 
                        targetAgent.getName(), httpStatus, params.toString());
            } else {
                executionResultText = String.format("Routed request to the **%s** agent, but the target endpoint returned an error status: HTTP %d. Extracted Parameters: %s", 
                        targetAgent.getName(), httpStatus, params.toString());
            }
        } catch (IOException e) {
            latency = (int) (System.currentTimeMillis() - execStart);
            log.warn("Failed to contact agent endpoint {}: {}", targetAgent.getEndpointUrl(), e.getMessage());
            executionResultText = String.format("Successfully matched intent to **%s** agent, but the developer-hosted endpoint (%s) was unreachable. Telemetry logged (HTTP 502 Bad Gateway). Extracted Parameters: %s", 
                    targetAgent.getName(), targetAgent.getEndpointUrl(), params.toString());
        }

        // 6. Log telemetry event
        int totalLatency = (int) (System.currentTimeMillis() - startTime);
        telemetryService.logRouting(workspaceId, userId, targetAgent.getId(), query, "routed", totalLatency, httpStatus);

        return SupervisorResponseDto.builder()
                .textResponse(executionResultText)
                .matchedAgent(targetAgent.getName())
                .extractedParameters(params)
                .latencyMs(latency)
                .status(httpStatus >= 200 && httpStatus < 300 ? "routed" : "failed")
                .build();
    }

    private Map<String, Object> extractParameters(AgentSearchMatchDto agent, String query) throws IOException {
        if ("mock-key".equalsIgnoreCase(apiKey)) {
            // Mock parameter extractor
            Map<String, Object> mockParams = new HashMap<>();
            for (AgentParameterDto p : agent.getParameters()) {
                if ("boolean".equalsIgnoreCase(p.getType())) {
                    mockParams.put(p.getName(), query.toLowerCase().contains("force") || query.toLowerCase().contains("true"));
                } else if ("number".equalsIgnoreCase(p.getType())) {
                    mockParams.put(p.getName(), 42); // standard mock number
                } else if ("array".equalsIgnoreCase(p.getType())) {
                    mockParams.put(p.getName(), List.of("mock-item-1", "mock-item-2"));
                } else {
                    // Extract URL if parameter name suggests it
                    if (p.getName().toLowerCase().contains("url") || p.getName().toLowerCase().contains("path")) {
                        mockParams.put(p.getName(), "https://files.internal/docs/document_sample.pdf");
                    } else {
                        mockParams.put(p.getName(), "mock-value-extracted");
                    }
                }
            }
            return mockParams;
        }

        // LLM parameter extraction via chat completions
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", "gpt-4o-mini");
        
        ArrayNode messages = payload.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are a precise data extractor. Extract parameters from the user's query for the target agent.\n" +
                "Agent Parameters specification:\n" +
                mapper.writeValueAsString(agent.getParameters()) + "\n" +
                "Output ONLY a raw JSON object containing parameter keys and values. Do not wrap in markdown quotes.");

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", query);

        Request req = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(mapper.writeValueAsBytes(payload), MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("OpenAI chat error: " + resp.code());
            }
            JsonNode root = mapper.readTree(resp.body().byteStream());
            String jsonText = root.path("choices").get(0).path("message").path("content").asText().trim();
            // In case model wraps in ```json ... ``` block
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.substring(jsonText.indexOf("{"), jsonText.lastIndexOf("}") + 1);
            }
            return mapper.readValue(jsonText, HashMap.class);
        }
    }

    private int dispatchAgentRequest(String endpointUrl, Map<String, Object> params) throws IOException {
        log.info("Dispatching execution payload to agent endpoint: {}", endpointUrl);
        
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
