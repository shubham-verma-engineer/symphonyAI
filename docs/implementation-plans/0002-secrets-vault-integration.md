# Implementation Plan: Secrets Vault Integration

| Field | Value |
|---|---|
| **Plan #** | 0002 |
| **Status** | Draft |
| **Branch** | `main` |
| **Author** | Antigravity + Shubham |
| **Created** | 2026-07-14 |
| **Last updated** | 2026-07-14 |
| **Related design doc** | `docs/SECRETS-MANAGEMENT.md` |

## 1. Understanding

The goal is to secure the storage of credentials/API keys for external developer agents. Currently, external agents are registered and executed without secure credential injection.
- Database protection: Plaintext API keys must never be stored in the database. The `agents` table should only store a reference URI (`credential_vault_uri`).
- Secure execution: During Supervisor routing and agent execution, the API key must be retrieved from the Secrets Vault on the fly, injected as an `Authorization: Bearer <token>` header in the outbound HTTP payload, and immediately purged from memory.
- Local implementation: Provide a simulated encrypted Secrets Vault using AES-256-GCM.

## 2. Approach

- **Schema Migration**: Add a new Flyway migration `V3__add_credential_vault_uri.sql` adding `credential_vault_uri` column to the `agents` table.
- **SecretsVaultService**: Define an interface and concrete implementation (`LocalSecretsVaultService`) encrypting credentials in-memory using AES-256-GCM.
- **DTO and Entity Updates**: Add `credentialVaultUri` field to `Agent` entity and `AgentSearchMatchDto`. Add `secretKey` to `SecurityDto` payload so creators can upload keys during manifest onboarding.
- **Onboarding Pipeline Integration**: In `AgentService.onboardAgent`, if `manifest.getSecurity().getSecretKey()` is provided, write it to `SecretsVaultService` and record the generated URI in the database.
- **Execution Injection**: In `SupervisorService.java`, fetch the secret from the vault during request dispatch, append the header to the OkHttp request, and discard the token.

## 3. Phases

### Phase 1 — Database & DTO Extensions · Status: Pending
- **Does**: Add Flyway migration and extend DTOs/Entities to support secret key inputs and vault URI mappings.
- **Verify**: Compile backend and run migrations.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/resources/db/migration/V3__add_credential_vault_uri.sql` | Migration script to add credential_vault_uri column to agents (new) |
  | `symphony-backend/src/main/java/com/symphony/entity/Agent.java` | Add credentialVaultUri mapping column (modified) |
  | `symphony-backend/src/main/java/com/symphony/dto/SecurityDto.java` | Add secretKey input field (modified) |
  | `symphony-backend/src/main/java/com/symphony/dto/AgentSearchMatchDto.java` | Add credentialVaultUri mapping property (modified) |

### Phase 2 — Secrets Vault Implementation & Service Hooking · Status: Pending
- **Does**: Implement the encrypted SecretsVaultService and integrate onboarding/execution pipelines.
- **Verify**: Run JUnit tests validating secret storing, retrieval, and request header injection.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/service/SecretsVaultService.java` | Define interface for storing and retrieving secrets (new) |
  | `symphony-backend/src/main/java/com/symphony/service/LocalSecretsVaultService.java` | Implementation of vault using AES-256-GCM encryption (new) |
  | `symphony-backend/src/main/java/com/symphony/service/AgentService.java` | Integrate SecretsVaultService to store keys during onboarding (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/SupervisorService.java` | Retrieve key and append Authorization Bearer headers in execution (modified) |

### Phase 3 — UI Key Integration · Status: Pending
- **Does**: Support optional key entry in the onboarding portal UI.
- **Verify**: Register an agent with a mock key and inspect database to verify the key is represented as a vault URI.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-ui/src/App.jsx` | Add secretKey parameter in manifest textarea (modified) |

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Plaintext keys leaked in logs | Purge decrypted tokens immediately after HTTP execution and avoid logging the token/headers containing it. |

## 5. Out of scope / deferred

- Setting up an actual HashiCorp Vault production container (simulated with in-memory AES-256-GCM vault).

## 6. Verification

- Write an integration test in `GovernanceOrchestrationIntegrationTest.java` verifying that an onboarded agent with a key results in the key being stored in the vault, and that execution retrieves and attaches the key.

## 7. Rollback

- Revert commits on `main`.

## 8. Decision Log

| Date | Change | Why |
|---|---|---|
| 2026-07-14 | Initial draft | Plan proposed for Secrets Vault Integration |

## 9. Commits / PR

- Commit: TBD
