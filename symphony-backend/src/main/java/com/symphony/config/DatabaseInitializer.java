package com.symphony.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;

    public static final UUID DEFAULT_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Override
    public void run(String... args) {
        log.info("Checking database initialization using JdbcTemplate...");

        Integer wsCount = jdbc.queryForObject("SELECT COUNT(*) FROM workspaces WHERE id = CAST(? AS UUID)", Integer.class, DEFAULT_WORKSPACE_ID.toString());
        if (wsCount == 0) {
            log.info("Creating default workspace...");
            jdbc.update("INSERT INTO workspaces (id, name, billing_tier) VALUES (CAST(? AS UUID), ?, ?)",
                    DEFAULT_WORKSPACE_ID.toString(), "Default Enterprise Workspace", "FREE");
        }

        Integer userCount = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = CAST(? AS UUID)", Integer.class, DEFAULT_USER_ID.toString());
        if (userCount == 0) {
            log.info("Creating default developer/admin user...");
            jdbc.update("INSERT INTO users (id, username, password_hash, email, role_flag) VALUES (CAST(? AS UUID), ?, ?, ?, ?)",
                    DEFAULT_USER_ID.toString(), "shubham", "$2a$10$xyz", "shubham@enterprise.com", "CREATOR");
        }

        Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_members WHERE workspace_id = CAST(? AS UUID) AND user_id = CAST(? AS UUID)",
                Integer.class, DEFAULT_WORKSPACE_ID.toString(), DEFAULT_USER_ID.toString());
        if (memberCount == 0) {
            log.info("Associating default user to default workspace...");
            jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (CAST(? AS UUID), CAST(? AS UUID), ?)",
                    DEFAULT_WORKSPACE_ID.toString(), DEFAULT_USER_ID.toString(), "OWNER");
        }

        log.info("Database initialization check complete.");
    }
}
