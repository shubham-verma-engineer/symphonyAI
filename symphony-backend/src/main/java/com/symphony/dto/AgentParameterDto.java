package com.symphony.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentParameterDto {

    @NotBlank(message = "parameter name is required")
    @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]*$", message = "parameter name must start with letter or underscore, alphanumeric only")
    private String name;

    @NotBlank(message = "parameter type is required")
    @Pattern(regexp = "^(string|number|boolean|array)$", message = "parameter type must be string, number, boolean, or array")
    private String type;

    @NotBlank(message = "parameter description is required")
    private String description;

    @NotNull(message = "required flag is required")
    private Boolean required;
}
