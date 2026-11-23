package com.symphony;

import com.symphony.config.DatabaseInitializer;
import com.symphony.config.JwtUtils;
import com.symphony.controller.AuthController;
import com.symphony.entity.User;
import com.symphony.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the authentication guarantees introduced by phase 2 of plan 0007.
 *
 * <p>Before that phase, {@code /api/v1/auth/login} accepted {@code {username,
 * role}} with no password, copied the caller-supplied role into the token's
 * {@code roles} claim, and fell back to the seeded owner account when the
 * username was unknown — so an unauthenticated caller could obtain an
 * owner-level token. Each test below pins one half of that fix.
 */
@SpringBootTest
public class AuthenticationIntegrationTest {

    @Autowired
    private AuthController authController;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtils jwtUtils;

    @Value("${symphony.dev.seed-password}")
    private String seedPassword;

    @Test
    void correctPasswordReturnsTokenCarryingDatabaseRoles() {
        var response = authController.login(
                new AuthController.LoginRequest("shubham", seedPassword));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        AuthController.LoginResponse body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getToken());

        User seeded = userRepository.findByUsername("shubham").orElseThrow();

        // The response and the token must both reflect the database, not the request.
        assertEquals(seeded.getRoleFlag(), body.getPlatformRole());
        assertEquals(seeded.getCorporateRoleList(), body.getRoles());

        Claims claims = jwtUtils.parseToken(body.getToken());
        assertEquals(seeded.getId().toString(), claims.getSubject());
        assertEquals(seeded.getRoleFlag(), claims.get("platform_role", String.class));
        assertEquals(seeded.getCorporateRoleList(), claims.get("roles", List.class));
    }

    @Test
    void wrongPasswordIsRejected() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                authController.login(new AuthController.LoginRequest("shubham", "definitely-not-the-password")));

        assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
    }

    @Test
    void unknownUsernameIsRejectedRatherThanFallingBackToTheDefaultUser() {
        ResponseStatusException thrown = assertThrows(ResponseStatusException.class, () ->
                authController.login(new AuthController.LoginRequest("no-such-user", seedPassword)));

        // The regression this guards: the old implementation resolved an unknown
        // username to DatabaseInitializer.DEFAULT_USER_ID and returned a valid
        // token for the seeded owner account.
        assertEquals(HttpStatus.UNAUTHORIZED, thrown.getStatusCode());
    }

    @Test
    void blankCredentialsAreRejected() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () ->
                        authController.login(new AuthController.LoginRequest("", ""))).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () ->
                        authController.login(new AuthController.LoginRequest("shubham", ""))).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED,
                assertThrows(ResponseStatusException.class, () ->
                        authController.login(new AuthController.LoginRequest(null, null))).getStatusCode());
    }

    @Test
    void lowPrivilegeUserCannotObtainElevatedRoles() {
        // The 'employee' seed account holds only the Employee corporate role and
        // the USER platform flag. There is no request field through which it
        // could ask for anything more — LoginRequest carries username and
        // password only.
        var response = authController.login(
                new AuthController.LoginRequest("employee", seedPassword));

        AuthController.LoginResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("USER", body.getPlatformRole());
        assertEquals(List.of("Employee"), body.getRoles());
        assertFalse(body.getRoles().contains("Admin"),
                "An employee account must not be able to acquire the Admin role");
    }

    @Test
    void seededUsersHaveRealBcryptHashesRatherThanThePlaceholder() {
        for (UUID id : List.of(DatabaseInitializer.DEFAULT_USER_ID,
                DatabaseInitializer.DEFAULT_HR_USER_ID,
                DatabaseInitializer.DEFAULT_EMPLOYEE_USER_ID)) {
            User user = userRepository.findById(id).orElseThrow();
            String hash = user.getPasswordHash();

            assertNotEquals("$2a$10$xyz", hash, "Placeholder hash is still in the database");
            assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"),
                    "Expected a BCrypt hash, got: " + hash);
            assertTrue(hash.length() >= 59, "BCrypt hashes are 60 characters; got " + hash.length());
            assertNotNull(user.getCorporateRoles(), "Seeded users must carry corporate roles");
        }
    }
}
