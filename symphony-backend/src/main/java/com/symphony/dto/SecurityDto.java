package com.symphony.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityDto {

    @NotNull(message = "requiresRBAC is required")
    private Boolean requiresRBAC;

    private List<String> authorizedRoles;
}
