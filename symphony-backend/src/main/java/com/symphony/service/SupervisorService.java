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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupervisorService {

    private final AgentService agentService;
    private final TelemetryService telemetryService;
    private final SecretsVaultService secretsVaultService;

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

        // --- PIPELINE 1: DYNAMIC DAG ORCHESTRATOR WORKFLOW ---
        if (isChainedRequest) {
            log.info("Supervisor triggering dynamic DAG orchestrator planning...");
            steps.add("Decomposed query: identified composite multi-agent requirements and initiating dynamic DAG planning...");

            Dag dag = planDag(workspaceId, query, candidates);
            if (dag.nodes.size() >= 2) {
                steps.add(String.format("Successfully planned DAG with %d execution nodes.", dag.nodes.size()));

                boolean dagSuccess = true;
                int executedCount = 0;
                int stepsCount = 1;
                String lastOutput = "Extracted document summaries and key action bullet points."; // Default mock start output
                UUID lastExecutedAgentId = null;
                int lastHttpStatusCode = 200;
                long totalExecutionLatency = 0;

                while (true) {
                    List<DagNode> runnableNodes = new ArrayList<>();
                    for (DagNode node : dag.nodes) {
                        if ("PENDING".equals(node.status)) {
                            boolean depsMet = true;
                            for (String depId : node.dependencies) {
                                DagNode depNode = dag.nodes.stream()
                                        .filter(n -> n.id.equals(depId))
                                        .findFirst()
                                        .orElse(null);
                                if (depNode == null || !"COMPLETED".equals(depNode.status)) {
                                    depsMet = false;
                                    break;
                                }
                            }
                            if (depsMet) {
                                runnableNodes.add(node);
                            }
                        }
                    }

                    if (runnableNodes.isEmpty()) {
                        boolean hasUnmet = dag.nodes.stream().anyMatch(n -> "PENDING".equals(n.status));
                        if (hasUnmet) {
                            dagSuccess = false;
                            steps.add("Pipeline Failure: Unmet dependencies or circular references detected in DAG.");
                        }
                        break;
                    }

                    for (DagNode node : runnableNodes) {
                        node.status = "RUNNING";
                        steps.add(String.format("Step %d: Planning execution for agent '%s' (%s)", stepsCount++, node.agentName, node.id));

                        // 1. RBAC Verification
                        AgentSearchMatchDto dummyMatch = AgentSearchMatchDto.builder()
                                .name(node.agentName)
                                .requiresRbac(node.requiresRbac)
                                .authorizedRoles(node.authorizedRoles)
                                .build();

                        if (isRbacBlocked(dummyMatch, userRole)) {
                            node.status = "FAILED";
                            dagSuccess = false;
                            steps.add(String.format("Security Halt: Role '%s' is unauthorized to execute agent '%s'.", userRole, node.agentName));
                            lastExecutedAgentId = node.agentId;
                            lastHttpStatusCode = 403;
                            break;
                        }

                        // 2. Map parameters
                        Map<String, Object> params = extractMockOrLlmParams(
                                AgentSearchMatchDto.builder()
                                        .name(node.agentName)
                                        .parameters(node.parametersList)
                                        .build(),
                                node.queryContext
                        );

                        // Inject outputs from parent dependencies
                        if (!node.dependencies.isEmpty()) {
                            List<String> parentOutputs = new ArrayList<>();
                            for (String depId : node.dependencies) {
                                dag.nodes.stream()
                                        .filter(n -> n.id.equals(depId))
                                        .findFirst()
                                        .ifPresent(depNode -> {
                                            if (depNode.output != null) {
                                                parentOutputs.add(depNode.output);
                                            }
                                        });
                            }

                            if (!parentOutputs.isEmpty()) {
                                String mergedParentOutput = String.join("\n", parentOutputs);
                                steps.add(String.format("Data Propagation: Injecting output from dependencies %s into '%s' inputs...", 
                                        node.dependencies, node.agentName));

                                String targetParamName = node.parametersList.stream()
                                        .map(AgentParameterDto::getName)
                                        .filter(name -> name.toLowerCase().contains("text") || name.toLowerCase().contains("content") || name.toLowerCase().contains("query"))
                                        .findFirst()
                                        .orElse(node.parametersList.isEmpty() ? "input" : node.parametersList.get(0).getName());
                                params.put(targetParamName, mergedParentOutput);
                            }
                        }

                        node.inputParameters = params;
                        steps.add(String.format("Inputs resolved for '%s': %s", node.agentName, params));

                        // 3. HTTP Execute Dispatch
                        long nodeStart = System.currentTimeMillis();
                        int httpStatus = 502;
                        String nodeOutput = "Mock execution output from " + node.agentName + ".";
                        try {
                            httpStatus = dispatchAgentRequest(node.endpointUrl, node.credentialVaultUri, params);
                            if (httpStatus >= 200 && httpStatus < 300) {
                                node.status = "COMPLETED";
                                node.output = nodeOutput;
                                lastOutput = nodeOutput;
                                steps.add(String.format("Executed '%s' -> responded successfully with HTTP %d", node.agentName, httpStatus));
                            } else {
                                if ("mock-key".equalsIgnoreCase(apiKey)) {
                                    node.status = "COMPLETED";
                                    node.output = nodeOutput;
                                    lastOutput = nodeOutput;
                                    steps.add(String.format("Mock Endpoint Bypass: Agent '%s' returned HTTP %d, mapped to success for local test.", node.agentName, httpStatus));
                                } else {
                                    node.status = "FAILED";
                                    dagSuccess = false;
                                    steps.add(String.format("Execution Error: Agent '%s' returned status HTTP %d.", node.agentName, httpStatus));
                                }
                            }
                        } catch (IOException e) {
                            if ("mock-key".equalsIgnoreCase(apiKey)) {
                                httpStatus = 200;
                                node.status = "COMPLETED";
                                node.output = nodeOutput;
                                lastOutput = nodeOutput;
                                steps.add(String.format("Mock Network Bypass: Simulated response for local test on offline agent '%s'.", node.agentName));
                            } else {
                                node.status = "FAILED";
                                dagSuccess = false;
                                steps.add(String.format("Network Error: Unreachable endpoint for agent '%s'.", node.agentName));
                            }
                        }

                        long nodeLatency = System.currentTimeMillis() - nodeStart;
                        totalExecutionLatency += nodeLatency;
                        lastExecutedAgentId = node.agentId;
                        lastHttpStatusCode = httpStatus;

                        if (!dagSuccess) {
                            break;
                        }

                        executedCount++;
                    }

                    if (!dagSuccess) {
                        break;
                    }
                }

                if (!dagSuccess) {
                    telemetryService.logRouting(workspaceId, userId, lastExecutedAgentId, query, "chained", (int) totalExecutionLatency, lastHttpStatusCode);
                    if (lastHttpStatusCode == 403) {
                        return createAccessDeniedResponse(
                                dag.nodes.stream().filter(n -> "FAILED".equals(n.status)).map(n -> n.agentName).findFirst().orElse("Unknown"),
                                userRole,
                                steps
                        );
                    }
                    return SupervisorResponseDto.builder()
                            .textResponse("Workflow pipeline execution failed at one of the steps. Check execution log details.")
                            .matchedAgent(dag.nodes.stream().map(n -> n.agentName).collect(Collectors.joining(" → ")))
                            .extractedParameters(new HashMap<>())
                            .latencyMs((int) totalExecutionLatency)
                            .status("failed")
                            .executionSteps(steps)
                            .build();
                }

                // Success
                String flowName = dag.nodes.stream().map(n -> n.agentName).collect(Collectors.joining(" → "));
                String summary = String.format("Dynamic DAG pipeline completed successfully. Output resolved through %d agents: %s\n[Final Output]: %s",
                        executedCount, flowName, lastOutput);

                telemetryService.logRouting(workspaceId, userId, lastExecutedAgentId, query, "chained", (int) totalExecutionLatency, 200);

                return SupervisorResponseDto.builder()
                        .textResponse(summary)
                        .matchedAgent(flowName)
                        .extractedParameters(dag.nodes.get(dag.nodes.size() - 1).inputParameters)
                        .latencyMs((int) totalExecutionLatency)
                        .status("routed")
                        .executionSteps(steps)
                        .build();
            } else {
                steps.add("Chaining requested, but dynamic DAG planning resulted in fewer than 2 agents. Falling back to single agent routing.");
            }
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
            httpStatus = dispatchAgentRequest(targetAgent.getEndpointUrl(), targetAgent.getCredentialVaultUri(), params);
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

    private int dispatchAgentRequest(String endpointUrl, String credentialVaultUri, Map<String, Object> params) throws IOException {
        String token = null;
        if (credentialVaultUri != null) {
            token = secretsVaultService.retrieveSecret(credentialVaultUri);
        }

        RequestBody body = RequestBody.create(
                mapper.writeValueAsBytes(params),
                MediaType.parse("application/json")
        );

        Request.Builder requestBuilder = new Request.Builder()
                .url(endpointUrl)
                .post(body);

        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer " + token);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.code();
        }
    }

    public static class DagNode {
        public String id;
        public String agentName;
        public UUID agentId;
        public String queryContext;
        public String endpointUrl;
        public String credentialVaultUri;
        public List<AgentParameterDto> parametersList;
        public Boolean requiresRbac;
        public List<String> authorizedRoles;
        public List<String> dependencies = new ArrayList<>();
        public Map<String, Object> inputParameters = new HashMap<>();
        public String output;
        public String status = "PENDING";
    }

    public static class Dag {
        public List<DagNode> nodes = new ArrayList<>();
    }

    private Dag planDag(UUID workspaceId, String query, List<AgentSearchMatchDto> candidates) {
        Dag dag = new Dag();
        if ("mock-key".equalsIgnoreCase(apiKey)) {
            String[] clauses = query.split("(?i)\\b(?:then|and then|next|and finally)\\b");
            int idGen = 1;
            for (int i = 0; i < clauses.length; i++) {
                String subQuery = clauses[i].trim();
                if (subQuery.isEmpty()) continue;

                AgentSearchMatchDto match = null;
                if (i < candidates.size()) {
                    match = candidates.get(i);
                } else if (!candidates.isEmpty()) {
                    match = candidates.get(candidates.size() - 1);
                }

                if (match != null) {
                    DagNode node = new DagNode();
                    node.id = "node_" + idGen;
                    node.agentName = match.getName();
                    node.agentId = match.getId();
                    node.queryContext = subQuery;
                    node.endpointUrl = match.getEndpointUrl();
                    node.credentialVaultUri = match.getCredentialVaultUri();
                    node.parametersList = match.getParameters();
                    node.requiresRbac = match.getRequiresRbac();
                    node.authorizedRoles = match.getAuthorizedRoles();

                    if (idGen > 1) {
                        node.dependencies.add("node_" + (idGen - 1));
                    }
                    dag.nodes.add(node);
                    idGen++;
                }
            }
            return dag;
        }

        try {
            List<AgentSearchMatchDto> allAgents = agentService.listAll(workspaceId);

            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", "gpt-4o-mini");
            ArrayNode messages = payload.putArray("messages");

            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "You are the Orchestration Planner for SymphonyAI.\n" +
                    "Decompose the user query into a Directed Acyclic Graph (DAG) of agent execution steps.\n" +
                    "Available agents catalog:\n" +
                    mapper.writeValueAsString(allAgents) + "\n" +
                    "Output ONLY a raw JSON object matching this schema:\n" +
                    "{\n" +
                    "  \"nodes\": [\n" +
                    "    {\n" +
                    "      \"id\": \"node_1\",\n" +
                    "      \"agentName\": \"summarizer-agent\",\n" +
                    "      \"queryContext\": \"sub-query text summarizing doc\",\n" +
                    "      \"dependencies\": []\n" +
                    "    },\n" +
                    "    {\n" +
                    "      \"id\": \"node_2\",\n" +
                    "      \"agentName\": \"translator-agent\",\n" +
                    "      \"queryContext\": \"translate sum to French\",\n" +
                    "      \"dependencies\": [\"node_1\"]\n" +
                    "    }\n" +
                    "  ]\n" +
                    "}");

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
                    JsonNode dagNode = mapper.readTree(jsonText);
                    JsonNode nodesArr = dagNode.path("nodes");
                    if (nodesArr.isArray()) {
                        int idGen = 1;
                        for (JsonNode n : nodesArr) {
                            String agentName = n.path("agentName").asText();
                            AgentSearchMatchDto matchedAgent = allAgents.stream()
                                    .filter(a -> a.getName().equalsIgnoreCase(agentName))
                                    .findFirst()
                                    .orElse(null);

                            if (matchedAgent != null) {
                                DagNode node = new DagNode();
                                node.id = n.path("id").asText("node_" + idGen);
                                node.agentName = matchedAgent.getName();
                                node.agentId = matchedAgent.getId();
                                node.queryContext = n.path("queryContext").asText(query);
                                node.endpointUrl = matchedAgent.getEndpointUrl();
                                node.credentialVaultUri = matchedAgent.getCredentialVaultUri();
                                node.parametersList = matchedAgent.getParameters();
                                node.requiresRbac = matchedAgent.getRequiresRbac();
                                node.authorizedRoles = matchedAgent.getAuthorizedRoles();

                                JsonNode depArr = n.path("dependencies");
                                if (depArr.isArray()) {
                                    for (JsonNode d : depArr) {
                                        node.dependencies.add(d.asText());
                                    }
                                }
                                dag.nodes.add(node);
                                idGen++;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Dynamic DAG planning failed: {}", e.getMessage());
        }
        return dag;
    }
}
