package com.symphony.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentManifestDto {

    @NotBlank(message = "manifestVersion is required")
    private String manifestVersion;

    @NotBlank(message = "name is required")
    @Pattern(regexp = "^[a-z0-9-]{3,50}$", message = "name must be 3-50 chars, alphanumeric and hyphens only")
    private String name;

    @NotBlank(message = "version is required")
    @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+$", message = "version must be in SemVer format (e.g. 1.0.0)")
    private String version;

    @NotBlank(message = "description is required")
    @Size(min = 10, max = 1000, message = "description must be between 10 and 1000 characters")
    private String description;

    @NotNull(message = "endpoints is required")
    @Valid
    private EndpointsDto endpoints;

    @NotNull(message = "parameters is required")
    @Valid
    private List<AgentParameterDto> parameters;

    @NotNull(message = "security is required")
    @Valid
    private SecurityDto security;
}
