package com.symphony.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seeds the default workspace and a set of development accounts.
 *
 * <p>Runs only when {@link MockModeProperties#isEnabled()} is true. Seeded
 * accounts exist so the platform is usable immediately on a fresh local
 * database; creating them in a shared environment would mean shipping known
 * credentials, so production provisions its users through its own process.
 *
 * <p>Each account carries both role layers described in
 * {@code docs/SECURITY-AND-ACCESS.md} section 2 — a platform {@code role_flag}
 * and a set of corporate roles — so the RBAC behaviour of a real deployment can
 * be exercised locally by logging in as different users rather than by naming a
 * role in the login request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final MockModeProperties mockMode;

    @Value("${symphony.dev.seed-password:}")
    private String seedPassword;

    public static final UUID DEFAULT_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID DEFAULT_HR_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    public static final UUID DEFAULT_EMPLOYEE_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    /** id, username, email, platform role flag, corporate roles. */
    private record SeedUser(UUID id, String username, String email, String roleFlag, String corporateRoles) {}

    private static final List<SeedUser> SEED_USERS = List.of(
            new SeedUser(DEFAULT_USER_ID, "shubham", "shubham@enterprise.com", "ADMIN", "Admin,HR"),
            new SeedUser(DEFAULT_HR_USER_ID, "hr.specialist", "hr.specialist@enterprise.com", "CREATOR", "HR"),
            new SeedUser(DEFAULT_EMPLOYEE_USER_ID, "employee", "employee@enterprise.com", "USER", "Employee")
    );

    @Override
    public void run(String... args) {
        if (!mockMode.isEnabled()) {
            log.info("Mock mode is off; skipping development data seeding.");
            return;
        }

        if (seedPassword == null || seedPassword.isBlank()) {
            log.warn("Mock mode is on but symphony.dev.seed-password is not set; skipping user seeding. "
                    + "Set SYMPHONY_DEV_SEED_PASSWORD to create the development accounts.");
            return;
        }

        log.info("Mock mode is on; checking development data seeding...");

        Integer wsCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspaces WHERE id = CAST(? AS UUID)",
                Integer.class, DEFAULT_WORKSPACE_ID.toString());
        if (wsCount != null && wsCount == 0) {
            log.info("Creating default workspace...");
            jdbc.update("INSERT INTO workspaces (id, name, billing_tier) VALUES (CAST(? AS UUID), ?, ?)",
                    DEFAULT_WORKSPACE_ID.toString(), "Default Enterprise Workspace", "FREE");
        }

        // One hash shared across the seeded accounts: they all use the same
        // development password, and hashing once keeps startup quick.
        String hashedSeedPassword = passwordEncoder.encode(seedPassword);

        for (SeedUser seed : SEED_USERS) {
            Integer userCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE id = CAST(? AS UUID)",
                    Integer.class, seed.id().toString());

            if (userCount != null && userCount == 0) {
                log.info("Creating development user '{}' (platform role {}, corporate roles {})...",
                        seed.username(), seed.roleFlag(), seed.corporateRoles());
                jdbc.update("INSERT INTO users (id, username, password_hash, email, role_flag, corporate_roles) "
                                + "VALUES (CAST(? AS UUID), ?, ?, ?, ?, ?)",
                        seed.id().toString(), seed.username(), hashedSeedPassword,
                        seed.email(), seed.roleFlag(), seed.corporateRoles());
            } else {
                // Re-hash on every boot so that changing the configured seed
                // password takes effect, and so databases created before
                // passwords were verified stop carrying the old placeholder hash.
                jdbc.update("UPDATE users SET password_hash = ?, corporate_roles = COALESCE(corporate_roles, ?) "
                                + "WHERE id = CAST(? AS UUID)",
                        hashedSeedPassword, seed.corporateRoles(), seed.id().toString());
            }

            Integer memberCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM workspace_members WHERE workspace_id = CAST(? AS UUID) "
                            + "AND user_id = CAST(? AS UUID)",
                    Integer.class, DEFAULT_WORKSPACE_ID.toString(), seed.id().toString());
            if (memberCount != null && memberCount == 0) {
                String membershipRole = "ADMIN".equals(seed.roleFlag()) ? "OWNER"
                        : "CREATOR".equals(seed.roleFlag()) ? "EDITOR" : "VIEWER";
                log.info("Associating '{}' to the default workspace as {}...", seed.username(), membershipRole);
                jdbc.update("INSERT INTO workspace_members (workspace_id, user_id, role) "
                                + "VALUES (CAST(? AS UUID), CAST(? AS UUID), ?)",
                        DEFAULT_WORKSPACE_ID.toString(), seed.id().toString(), membershipRole);
            }
        }

        log.info("Development data seeding complete. Accounts: {}",
                SEED_USERS.stream().map(SeedUser::username).toList());
    }
}
