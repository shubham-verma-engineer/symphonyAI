package com.symphony.service;

import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.AgentParameterDto;
import com.symphony.dto.AgentSearchMatchDto;
import com.symphony.entity.Agent;
import com.symphony.entity.AgentParameter;
import com.symphony.repository.AgentParameterRepository;
import com.symphony.repository.AgentRepository;
import com.symphony.service.openai.OpenAiEmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final AgentRepository agentRepository;
    private final AgentParameterRepository parameterRepository;
    private final OpenAiEmbeddingClient embeddingClient;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(3))
            .build();

    @Transactional
    public Agent onboardAgent(AgentManifestDto manifest, UUID workspaceId, UUID userId) {
        log.info("Onboarding agent '{}' version '{}' in workspace '{}'", manifest.getName(), manifest.getVersion(), workspaceId);

        // 1. Perform loopback health check ping
        pingHealthEndpoint(manifest.getEndpoints().getHealth());

        // 2. Obtain description embedding vector from OpenAI
        float[] embedding;
        try {
            embedding = embeddingClient.embed(manifest.getDescription());
        } catch (IOException e) {
            log.error("Failed to generate embedding for agent '{}': {}", manifest.getName(), e.getMessage());
            throw new RuntimeException("OpenAI embedding generation failed: " + e.getMessage(), e);
        }

        // 3. Make onboarding idempotent: delete existing agent with identical name in workspace
        agentRepository.findByWorkspaceIdAndName(workspaceId, manifest.getName())
                .ifPresent(existing -> {
                    log.info("Removing existing agent registry entry for '{}' (ID: {})", existing.getName(), existing.getId());
                    parameterRepository.deleteAllByAgentId(existing.getId());
                    agentRepository.delete(existing);
                    agentRepository.flush();
                });

        // 4. Save metadata properties
        Agent agent = Agent.builder()
                .workspaceId(workspaceId)
                .name(manifest.getName())
                .version(manifest.getVersion())
                .description(manifest.getDescription())
                .endpointUrl(manifest.getEndpoints().getExecution())
                .healthCheckUrl(manifest.getEndpoints().getHealth())
                .status("ACTIVE")
                .createdBy(userId)
                .build();

        Agent savedAgent = agentRepository.save(agent);
        agentRepository.flush();

        // 5. Update embedding vector column (pgvector)
        String embeddingLiteral = toPgVectorLiteral(embedding);
        agentRepository.updateEmbedding(savedAgent.getId(), embeddingLiteral);

        // 6. Save parameters list
        if (manifest.getParameters() != null) {
            List<AgentParameter> params = manifest.getParameters().stream()
                    .map(dto -> AgentParameter.builder()
                            .agentId(savedAgent.getId())
                            .paramName(dto.getName())
                            .paramType(dto.getType())
                            .paramDescription(dto.getDescription())
                            .isRequired(dto.getRequired())
                            .build())
                    .collect(Collectors.toList());
            parameterRepository.saveAll(params);
        }

        log.info("Successfully onboarded agent '{}' with ID: {}", savedAgent.getName(), savedAgent.getId());
        return savedAgent;
    }

    public List<AgentSearchMatchDto> search(UUID workspaceId, String query, int limit) {
        log.info("Searching agents semantically for query '{}'", query);

        float[] embedding;
        try {
            embedding = embeddingClient.embed(query);
        } catch (IOException e) {
            log.error("Failed to generate embedding for search query: {}", e.getMessage());
            throw new RuntimeException("OpenAI embedding failed: " + e.getMessage(), e);
        }

        String embeddingLiteral = toPgVectorLiteral(embedding);
        List<Object[]> rows = agentRepository.searchAgentsSemantically(workspaceId, embeddingLiteral, limit);

        List<AgentSearchMatchDto> matches = new ArrayList<>();
        for (Object[] row : rows) {
            // Reconstruct agent columns: id, workspace_id, name, version, description, endpoint_url, health_check_url, status
            // The mapping order from SELECT a.* corresponds to PostgreSQL schema:
            // 0: id (UUID)
            // 1: workspace_id (UUID)
            // 2: name (String)
            // 3: version (String)
            // 4: description (String)
            // 5: description_embedding (skipped/pgvector)
            // 6: endpoint_url (String)
            // 7: health_check_url (String)
            // 8: status (String)
            // 9: created_by (UUID)
            // 10: registered_at (OffsetDateTime)
            // 11: concurrency_version (Integer)
            // And 12 is similarity (Double)
            
            UUID id = (UUID) row[0];
            String name = (String) row[2];
            String version = (String) row[3];
            String description = (String) row[4];
            String endpointUrl = (String) row[6];
            String healthCheckUrl = (String) row[7];
            String status = (String) row[8];
            Double similarity = (Double) row[12];

            List<AgentParameterDto> params = parameterRepository.findAllByAgentId(id).stream()
                    .map(p -> AgentParameterDto.builder()
                            .name(p.getParamName())
                            .type(p.getParamType())
                            .description(p.getParamDescription())
                            .required(p.getIsRequired())
                            .build())
                    .collect(Collectors.toList());

            matches.add(AgentSearchMatchDto.builder()
                    .id(id)
                    .name(name)
                    .version(version)
                    .description(description)
                    .endpointUrl(endpointUrl)
                    .healthCheckUrl(healthCheckUrl)
                    .status(status)
                    .similarity(similarity != null ? similarity : 0.0)
                    .parameters(params)
                    .build());
        }

        return matches;
    }

    public List<AgentSearchMatchDto> listAll(UUID workspaceId) {
        return agentRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(a -> {
                    List<AgentParameterDto> params = parameterRepository.findAllByAgentId(a.getId()).stream()
                            .map(p -> AgentParameterDto.builder()
                                    .name(p.getParamName())
                                    .type(p.getParamType())
                                    .description(p.getParamDescription())
                                    .required(p.getIsRequired())
                                    .build())
                            .collect(Collectors.toList());

                    return AgentSearchMatchDto.builder()
                            .id(a.getId())
                            .name(a.getName())
                            .version(a.getVersion())
                            .description(a.getDescription())
                            .endpointUrl(a.getEndpointUrl())
                            .healthCheckUrl(a.getHealthCheckUrl())
                            .status(a.getStatus())
                            .similarity(1.0)
                            .parameters(params)
                            .build();
                })
                .collect(Collectors.toList());
    }

    private void pingHealthEndpoint(String healthUrl) {
        log.info("Pinging health check endpoint: {}", healthUrl);
        Request request = new Request.Builder()
                .url(healthUrl)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalArgumentException("Health check returned status code " + response.code());
            }
        } catch (IOException e) {
            log.warn("Health check connection failed for URL: {}", healthUrl, e);
            throw new IllegalArgumentException("Health check endpoint unreachable: " + e.getMessage(), e);
        }
    }

    private String toPgVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 10 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
