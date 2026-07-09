package com.symphony.dto;

import lombok.*;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupervisorResponseDto {
    private String textResponse;
    private String matchedAgent;
    private Map<String, Object> extractedParameters;
    private int latencyMs;
    private String status; // 'routed', 'no_match', 'failed', 'forbidden'
    private boolean piiSanitized;
    private java.util.List<String> sanitizedFields;
    private java.util.List<String> executionSteps;
}
