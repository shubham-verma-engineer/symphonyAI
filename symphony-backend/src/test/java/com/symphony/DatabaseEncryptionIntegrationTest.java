package com.symphony;

import com.symphony.config.DatabaseInitializer;
import com.symphony.entity.FailedQuery;
import com.symphony.entity.TelemetryLog;
import com.symphony.repository.FailedQueryRepository;
import com.symphony.repository.TelemetryLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class DatabaseEncryptionIntegrationTest {

    @Autowired
    private TelemetryLogRepository telemetryLogRepository;

    @Autowired
    private FailedQueryRepository failedQueryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testTelemetryLogAndFailedQueryColumnEncryption() {
        UUID wsId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
        UUID userId = DatabaseInitializer.DEFAULT_USER_ID;

        // 1. Insert Telemetry Log
        String telemetryQueryText = "Secure database encryption query text check 123";
        TelemetryLog log = TelemetryLog.builder()
                .workspaceId(wsId)
                .userId(userId)
                .queryText(telemetryQueryText)
                .routingDecision("no_match")
                .executionLatencyMs(150)
                .tokenCost(BigDecimal.ZERO)
                .httpStatusCode(404)
                .build();

        TelemetryLog savedLog = telemetryLogRepository.saveAndFlush(log);
        assertNotNull(savedLog.getId());

        // 2. Fetch via JPA Repository (should decrypt transparently)
        TelemetryLog fetchedLog = telemetryLogRepository.findById(savedLog.getId()).orElse(null);
        assertNotNull(fetchedLog);
        assertEquals(telemetryQueryText, fetchedLog.getQueryText());

        // 3. Query directly using JdbcTemplate (should be encrypted in-DB)
        String rawTelemetryQuery = jdbcTemplate.queryForObject(
                "SELECT query_text FROM telemetry_logs WHERE id = ?",
                String.class,
                savedLog.getId()
        );
        assertNotNull(rawTelemetryQuery);
        assertNotEquals(telemetryQueryText, rawTelemetryQuery);

        // Verify it is Base64 encoded
        byte[] decodedBytes = Base64.getDecoder().decode(rawTelemetryQuery);
        assertTrue(decodedBytes.length > 12); // GCM IV + ciphertext length should be > 12

        // 4. Insert Failed Query
        String failedQueryText = "Unresolved mock payroll invoice query detail";
        FailedQuery failedQuery = FailedQuery.builder()
                .workspaceId(wsId)
                .rawQuery(failedQueryText)
                .isClustered(false)
                .build();

        FailedQuery savedFailed = failedQueryRepository.saveAndFlush(failedQuery);
        assertNotNull(savedFailed.getId());

        // 5. Fetch via JPA Repository (should decrypt transparently)
        FailedQuery fetchedFailed = failedQueryRepository.findById(savedFailed.getId()).orElse(null);
        assertNotNull(fetchedFailed);
        assertEquals(failedQueryText, fetchedFailed.getRawQuery());

        // 6. Query directly using JdbcTemplate (should be encrypted in-DB)
        String rawFailedQuery = jdbcTemplate.queryForObject(
                "SELECT raw_query FROM failed_queries WHERE id = ?",
                String.class,
                savedFailed.getId()
        );
        assertNotNull(rawFailedQuery);
        assertNotEquals(failedQueryText, rawFailedQuery);

        // Verify it is Base64 encoded
        byte[] decodedFailedBytes = Base64.getDecoder().decode(rawFailedQuery);
        assertTrue(decodedFailedBytes.length > 12);
    }
}
