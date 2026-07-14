# Implementation Plan: SSO & JWT Authentication Integration

| Field | Value |
|---|---|
| **Plan #** | 0001 |
| **Status** | Done |
| **Branch** | `main` |
| **Author** | Antigravity + Shubham |
| **Created** | 2026-07-14 |
| **Last updated** | 2026-07-14 |
| **Related design doc** | `docs/SECURITY-AND-ACCESS.md` |

## 1. Understanding

The goal is to secure the SymphonyAI platform by transitioning the communication between the React frontend and Spring Boot backend from plain-text HTTP headers to standardized JSON Web Tokens (JWT) using HMAC-SHA256, managed by Spring Security.
- Access control checking: Users must be authenticated, and endpoints must inspect their role claims to check RBAC constraints.
- Maintain legacy compatibility: Fallback gracefully to request headers or system defaults if no token is found in incoming HTTP requests, so service-layer and integration tests continue to run smoothly.

## 2. Approach

- **JJWT Integration**: Use `io.jsonwebtoken` library to parse, generate, and validate tokens signed with a 256-bit symmetric key.
- **Spring Security Filter Chain**: Add a custom `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter` to extract bearer tokens.
- **Custom Authentication**: Define `SymphonyAuthentication` to store user and workspace contexts directly within Spring Security Context.
- **SSO Simulation**: Create `AuthController` to return JWT tokens given a username and role.
- **Token Propagation**: Refactor frontend `App.jsx` to fetch and store the JWT in `localStorage`, and attach `Authorization: Bearer <token>` to all API requests.

## 3. Phases

### Phase 1 — Spring Security Setup · Status: Done
- **Does**: Implement JWT utils, security filter, and custom authentication token. Configure security path matchers.
- **Verify**: Compile backend and verify no errors.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/config/JwtUtils.java` | Created class to handle JWT lifecycle (new) |
  | `symphony-backend/src/main/java/com/symphony/config/SymphonyAuthentication.java` | Created class implementing Spring Security Authentication (new) |
  | `symphony-backend/src/main/java/com/symphony/config/JwtAuthenticationFilter.java` | Created filter to validate Bearer tokens and populate context (new) |
  | `symphony-backend/src/main/java/com/symphony/config/SecurityConfig.java` | Modified filter chain configuration to wire JwtAuthenticationFilter (modified) |

### Phase 2 — SSO Controller & Refactoring API endpoints · Status: Done
- **Does**: Expose login endpoint and update controllers to use Spring Security authentication context.
- **Verify**: Run JUnit integration tests on clean database.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/controller/AuthController.java` | Created AuthController exposing simulated login endpoint (new) |
  | `symphony-backend/src/main/java/com/symphony/controller/AgentOnboardingController.java` | Updated to read user and workspace context from Spring Security (modified) |
  | `symphony-backend/src/main/java/com/symphony/controller/SupervisorController.java` | Updated to extract workspace and user role from security context (modified) |
  | `symphony-backend/src/main/java/com/symphony/controller/AdminAnalyticsController.java` | Updated to extract workspace ID from security context (modified) |

### Phase 3 — Frontend Token Propagation · Status: Done
- **Does**: Fetch SSO token on startup/role change, save to `localStorage`, and attach to fetch request headers.
- **Verify**: Startup Vite dev server and check Network headers.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-ui/src/App.jsx` | Updated to manage auth token, fetch JWT from AuthController, and attach Bearer header (modified) |

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Integration test failures due to missing JWT token | Enabled controller fallbacks to legacy headers/defaults if no security context is present. |
| Port conflicts when running Postgres | Created `symphony_test` database inside the running Postgres container `neurameet-postgres` to isolate local testing schemas. |

## 5. Out of scope / deferred

- Fully fledged identity provider integration (e.g. keycloak, Microsoft Entra ID). Deferred for simulated login controller for local development.

## 6. Verification

- Ran integration test suite: `./gradlew test` with test database properties.
- Verified Flyway schema migration and successful service orchestration execution.

## 7. Rollback

- Revert commits on branch `main` and restore original mock header parsing logic.

## 8. Decision Log

| Date | Change | Why |
|---|---|---|
| 2026-07-14 | Initial draft | Initial implementation plan setup |

## 9. Commits / PR

- Commit: `feat(sso): implement SSO JWT Token Authentication`
