# Implementation Plan: Token Cost Allocation & Department Billing Dashboard

| Field | Value |
|---|---|
| **Plan #** | 0005 |
| **Status** | Draft |
| **Branch** | `main` |
| **Author** | Antigravity + Shubham |
| **Created** | 2026-07-14 |
| **Last updated** | 2026-07-14 |
| **Related design doc** | `docs/SYSTEM_DESIGN.md` |

## 1. Understanding

The goal is to implement token cost allocation metrics and attribute consumption back to SSO user roles (acting as department profiles) on an Admin Billing Dashboard.
- Database Schema: Add `department` column to `telemetry_logs` table via Flyway migration.
- Telemetry Attribution: Enhance `TelemetryLog` entity and `TelemetryService` to accept and record the request's user role (e.g. Employee, HR, Admin) and apply cost weights based on routing type (single, chained, fail).
- Analytics API: Add `/api/v1/admin/billing/stats` and `/api/v1/admin/billing/audit` endpoints returning department breakdowns and billing audits.
- Frontend Dashboard: Implement an interactive Billing Allocation Panel on the React Admin dashboard, rendering beautiful token cost charts (pie/bar) and data tables using CSS grid and clean statistics widgets.

## 2. Approach

- **Schema Migration**:
  - Create `V5__add_department_to_telemetry.sql` executing `ALTER TABLE telemetry_logs ADD COLUMN department VARCHAR(50);`.
- **Backend Service Changes**:
  - Update `TelemetryLog.java` to support `@Column(name = "department") String department`.
  - Modify `TelemetryService.java` to store `department` and simulate dynamic token costs (`0.00045` for chained, `0.00015` for routed, `0.00005` for others).
  - Modify all `telemetryService.logRouting` calls inside `SupervisorService.java` to supply the request's `userRole`.
- **REST Controller**:
  - Define `/api/v1/admin/billing/stats` to return aggregated stats grouped by department.
  - Define `/api/v1/admin/billing/audit` to return raw telemetry log details (timestamp, decision, cost, department, status, queryText).
- **Frontend Dashboard**:
  - Add a **Billing Panel** sub-view inside the admin view of the React frontend (`App.jsx`).
  - Render department token costs, query frequency, and average latency stats.
  - Design a high-fidelity audit trail table listing recent queries, costs, and department attributions.

## 3. Phases

### Phase 1 — Database & Entity Modifications · Status: Pending
- **Does**: Write Flyway schema migration and map the new attribute to the Hibernate JPA entity.
- **Verify**: Spin up context and check database updates.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/resources/db/migration/V5__add_department_to_telemetry.sql` | SQL Flyway schema script (new) |
  | `symphony-backend/src/main/java/com/symphony/entity/TelemetryLog.java` | Map database department column to model field (modified) |

### Phase 2 — Service Logic & Controller Endpoints · Status: Pending
- **Does**: Update telemetry logger parameter signature, calculate dynamic pricing, and expose billing APIs.
- **Verify**: Compile backend and write integration tests.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/repository/TelemetryLogRepository.java` | Add aggregated department stats native query (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/TelemetryService.java` | Calculate dynamic costs and record departments (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/SupervisorService.java` | Pass userRole into routing telemetry log triggers (modified) |
  | `symphony-backend/src/main/java/com/symphony/controller/AdminAnalyticsController.java` | Expose endpoint for stats and audits (modified) |

### Phase 3 — React Billing Dashboard Dashboard UI · Status: Pending
- **Does**: Design the billing panel UI sub-view inside `App.jsx` with CSS styling.
- **Verify**: Check UI in browser.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-ui/src/App.jsx` | Implement billing view sub-routes and display metrics (modified) |

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Null values in legacy telemetry records | Set a default value of `'Employee'` in the native query or migration script for existing logs to avoid null handling errors. |

## 5. Verification

- Write `BillingAllocationIntegrationTest.java` invoking billing endpoints and asserting correct calculations.
- Deploy and verify visually on the admin analytics screen.

## 6. Rollback

- Revert the changes to the database and services.

## 7. Decision Log

| Date | Change | Why |
|---|---|---|
| 2026-07-14 | Initial draft | Proposed plan for token allocation & billing dashboard |
