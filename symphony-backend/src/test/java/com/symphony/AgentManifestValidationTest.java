package com.symphony;

import com.symphony.dto.AgentManifestDto;
import com.symphony.dto.EndpointsDto;
import com.symphony.dto.SecurityDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AgentManifestValidationTest {

    private static Validator validator;

    @BeforeAll
    public static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    public void testValidManifest() {
        AgentManifestDto manifest = AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("legal-evaluator")
                .version("1.0.0")
                .description("Detailed description of legal parser bot.")
                .endpoints(EndpointsDto.builder()
                        .execution("https://legal.internal/execute")
                        .health("https://legal.internal/health")
                        .build())
                .parameters(Collections.emptyList())
                .security(SecurityDto.builder()
                        .requiresRBAC(false)
                        .build())
                .build();

        Set<ConstraintViolation<AgentManifestDto>> violations = validator.validate(manifest);
        assertTrue(violations.isEmpty(), "Valid manifest should have no constraint violations");
    }

    @Test
    public void testInvalidManifestName() {
        AgentManifestDto manifest = AgentManifestDto.builder()
                .manifestVersion("1.0")
                .name("INVALID_NAME_WITH_CAPS") // Violates pattern "^[a-z0-9-]{3,50}$"
                .version("1.0.0")
                .description("Detailed description of legal parser bot.")
                .endpoints(EndpointsDto.builder()
                        .execution("https://legal.internal/execute")
                        .health("https://legal.internal/health")
                        .build())
                .parameters(Collections.emptyList())
                .security(SecurityDto.builder()
                        .requiresRBAC(false)
                        .build())
                .build();

        Set<ConstraintViolation<AgentManifestDto>> violations = validator.validate(manifest);
        assertFalse(violations.isEmpty(), "Invalid name should trigger validation constraint violations");
    }
}
