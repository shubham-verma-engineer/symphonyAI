# Implementation Plan: Real-time Agent Health Monitor & Automated Heartbeat Checker

| Field | Value |
|---|---|
| **Plan #** | 0006 |
| **Status** | Done |
| **Branch** | `main` |
| **Author** | Antigravity + Shubham |
| **Created** | 2026-07-14 |
| **Last updated** | 2026-10-13 |
| **Related design doc** | `docs/SYSTEM_DESIGN.md` |

## 1. Understanding

The goal is to implement a background health checker that pings registered agents periodically and updates their status in the registry.
- Background Scheduling: Enable Spring Boot task scheduling (`@EnableScheduling`) on the Application starter class.
- Automated Heartbeat checker: Create `AgentHeartbeatService` running a `@Scheduled` background method checking all onboarded agents.
- Registry Synchronization: Automatically check `/health` endpoints and update the agent status flag inside the `agents` database table to `ACTIVE` or `UNHEALTHY` dynamically.
- Mock Bypass: To preserve local testing functionality, automatically resolve mock/localhost endpoints to `ACTIVE` status without firing real network calls.

## 2. Approach

- **Scheduling Enablement**:
  - Add `@EnableScheduling` to `Application.java`.
- **Scheduled Heartbeat Checker**:
  - Create `AgentHeartbeatService.java` containing the scheduled task `runHeartbeatCheck()` executing every 30 seconds (configurable via property `symphony.heartbeat.interval-ms`).
  - Read all agent entries, perform HTTP GET pings on `/health` URLs, and save the updated status.
- **Verification**:
  - Implement `AgentHeartbeatIntegrationTest.java` running simulated pings and verifying database status updates.

## 3. Phases

### Phase 1 — Enable Scheduling & Create Heartbeat Service · Status: Done
- **Does**: Enable scheduling in the main application class and build the scheduled service class.
- **Verify**: Compile backend and verify service class loads correctly.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/Application.java` | Add `@EnableScheduling` class annotation (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/AgentHeartbeatService.java` | Background scheduled task service logic (new) |

### Phase 2 — Verification & Test Suites · Status: Done
- **Does**: Write integration tests to check dynamic status changes based on endpoint responses.
- **Verify**: Run JUnit test suite.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/test/java/com/symphony/AgentHeartbeatIntegrationTest.java` | Integration test class for background heartbeat check (new) |

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| DB Connection Pool exhaustion during periodic updates | Perform checks asynchronously or handle exceptions gracefully so failures do not lock the transaction database scope. |

## 5. Verification

- Run `AgentHeartbeatIntegrationTest.java` test cases.

## 6. Rollback

- Remove the `@EnableScheduling` and delete the `AgentHeartbeatService.java` file.

## 7. Decision Log

| Date | Change | Why |
|---|---|---|
| 2026-07-14 | Initial draft | Proposed plan for real-time heartbeat checks |
| 2026-10-13 | Marked Done; both phases complete | Implemented in commit `31cbadd`; `@EnableScheduling` on `Application.java`, `AgentHeartbeatService.runHeartbeatCheck()` scheduled at `symphony.heartbeat.interval-ms` (default 30000ms), and `AgentHeartbeatIntegrationTest` asserting status transitions |
| 2026-10-13 | Unhealthy status named `UNHEALTHY`, not `OFFLINE` | The `agents.status` CHECK constraint in `V1__init_schema.sql` permits only `PENDING`, `ACTIVE`, `UNHEALTHY`, `DEPRECATED`; writing `OFFLINE` would violate it |
