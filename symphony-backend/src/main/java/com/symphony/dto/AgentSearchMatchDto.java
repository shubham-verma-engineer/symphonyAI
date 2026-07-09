package com.symphony.dto;

import lombok.*;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSearchMatchDto {
    private UUID id;
    private String name;
    private String version;
    private String description;
    private String endpointUrl;
    private String healthCheckUrl;
    private String status;
    private double similarity;
    private List<AgentParameterDto> parameters;
    private Boolean requiresRbac;
    private List<String> authorizedRoles;
}
