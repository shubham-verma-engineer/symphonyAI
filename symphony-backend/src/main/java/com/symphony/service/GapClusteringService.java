package com.symphony.service;

import com.symphony.config.MockModeProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.symphony.entity.FailedQuery;
import com.symphony.entity.InnovationGap;
import com.symphony.repository.FailedQueryRepository;
import com.symphony.repository.InnovationGapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GapClusteringService {

    private final FailedQueryRepository failedQueryRepository;
    private final InnovationGapRepository gapRepository;
    private final MockModeProperties mockMode;

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String baseUrl;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Transactional
    public void runClusteringPipeline(UUID workspaceId) {
        log.info("Running Innovation Gap Clustering Pipeline for workspace '{}'...", workspaceId);

        List<FailedQuery> unclustered = failedQueryRepository.findAllByWorkspaceIdAndIsClusteredFalse(workspaceId);
        if (unclustered.isEmpty()) {
            log.info("No unclustered failed queries found. Pipeline idle.");
            return;
        }

        List<String> queryStrings = unclustered.stream()
                .map(FailedQuery::getRawQuery)
                .collect(Collectors.toList());

        try {
            if (mockMode.isLlmMocked()) {
                runMockClustering(workspaceId, queryStrings);
            } else {
                runLlmClustering(workspaceId, queryStrings);
            }

            // Mark queries as clustered
            for (FailedQuery q : unclustered) {
                q.setIsClustered(true);
            }
            failedQueryRepository.saveAll(unclustered);
            log.info("Successfully clustered {} failed queries.", unclustered.size());

        } catch (Exception e) {
            log.error("Failed to run gap clustering pipeline", e);
            throw new RuntimeException("Clustering pipeline error: " + e.getMessage(), e);
        }
    }

    private void runMockClustering(UUID workspaceId, List<String> queries) {
        log.info("Running mock clustering for {} query strings...", queries.size());
        
        for (String q : queries) {
            String title;
            String summary;
            
            String lower = q.toLowerCase();
            if (lower.contains("slack") || lower.contains("message") || lower.contains("chat")) {
                title = "Slack Connector Integration";
                summary = "Automated posting and retrieval of corporate messages and status updates to/from Slack channels.";
            } else if (lower.contains("jira") || lower.contains("ticket") || lower.contains("bug")) {
                title = "Jira Ticket Automation Service";
                summary = "Automatically files support tickets, assigns developers, and updates bug states in Jira.";
            } else if (lower.contains("mail") || lower.contains("email") || lower.contains("outlook")) {
                title = "Email Notification Service";
                summary = "Provides automated client notification, mailing lists, and calendar integration.";
            } else {
                title = "General Automation: " + (q.length() > 30 ? q.substring(0, 30) + "..." : q);
                summary = "Corporate demand mapping for query statement: " + q;
            }

            saveOrIncrementGap(workspaceId, title, summary);
        }
    }

    private void runLlmClustering(UUID workspaceId, List<String> queries) throws IOException {
        log.info("Running LLM clustering for {} queries...", queries.size());

        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", "gpt-4o-mini");
        
        ArrayNode messages = payload.putArray("messages");
        ObjectNode systemMsg = messages.addObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", "You are an analytics engine. Cluster the provided list of unaddressed user search queries " +
                "into up to 5 distinct feature gaps. Output ONLY a valid JSON list of objects containing fields: " +
                "'title' (short descriptive feature request), 'summary' (brief details), and 'frequency' (count of queries in this cluster).\n" +
                "Do not include markdown tags.");

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", mapper.writeValueAsString(queries));

        Request req = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(mapper.writeValueAsBytes(payload), MediaType.parse("application/json")))
                .build();

        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("OpenAI completions status: " + resp.code());
            }
            JsonNode root = mapper.readTree(resp.body().byteStream());
            String jsonText = root.path("choices").get(0).path("message").path("content").asText().trim();
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.substring(jsonText.indexOf("["), jsonText.lastIndexOf("]") + 1);
            }

            JsonNode listNode = mapper.readTree(jsonText);
            if (listNode.isArray()) {
                for (JsonNode item : listNode) {
                    String title = item.path("title").asText();
                    String summary = item.path("summary").asText();
                    int freq = item.path("frequency").asInt(1);

                    Optional<InnovationGap> existing = gapRepository.findByWorkspaceIdAndTitleIgnoreCase(workspaceId, title);
                    if (existing.isPresent()) {
                        InnovationGap gap = existing.get();
                        gap.setCountFrequency(gap.getCountFrequency() + freq);
                        gapRepository.save(gap);
                    } else {
                        gapRepository.save(InnovationGap.builder()
                                .workspaceId(workspaceId)
                                .title(title)
                                .summary(summary)
                                .countFrequency(freq)
                                .build());
                    }
                }
            }
        }
    }

    private void saveOrIncrementGap(UUID workspaceId, String title, String summary) {
        Optional<InnovationGap> existing = gapRepository.findByWorkspaceIdAndTitleIgnoreCase(workspaceId, title);
        if (existing.isPresent()) {
            InnovationGap gap = existing.get();
            gap.setCountFrequency(gap.getCountFrequency() + 1);
            gapRepository.save(gap);
        } else {
            gapRepository.save(InnovationGap.builder()
                    .workspaceId(workspaceId)
                    .title(title)
                    .summary(summary)
                    .countFrequency(1)
                    .build());
        }
    }
}
