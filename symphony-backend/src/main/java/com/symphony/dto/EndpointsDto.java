package com.symphony.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndpointsDto {

    @NotBlank(message = "execution endpoint is required")
    private String execution;

    @NotBlank(message = "health endpoint is required")
    private String health;
}
