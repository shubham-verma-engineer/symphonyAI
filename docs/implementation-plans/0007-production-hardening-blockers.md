# Implementation Plan: Production Hardening — Ship-Blocker Remediation

| Field | Value |
|---|---|
| **Plan #** | 0007 |
| **Status** | Approved |
| **Branch** | `feature/0007-production-hardening` |
| **Author** | Claude + Shubham |
| **Created** | 2026-10-13 |
| **Last updated** | 2026-10-13 |
| **Related design doc** | `docs/SECURITY-AND-ACCESS.md`, `docs/SECRETS-MANAGEMENT.md` |

## 1. Understanding

Plans 0001–0006 delivered all three product pillars, and the platform demos end to end. But every one of those plans deliberately took a local-development shortcut, and each shortcut is still in place. None of them is a bug in isolation — they were correct decisions for building features fast. Together they mean the platform cannot be deployed to any shared environment, because the shortcuts are the **default** behaviour rather than an opt-in mode.

This plan closes the six defects that block deployment. It is remediation and configuration work: no new product capability, no schema change beyond one seed correction.

### 1.1 The six blockers

**B1 — `/api/v1/auth/login` verifies no credential and lets the caller assign its own role.**
`AuthController.login()` accepts `{username, role}`. There is no password field, no `PasswordEncoder` bean anywhere in the codebase, and `users.password_hash` is seeded with the literal placeholder `"$2a$10$xyz"` (not a valid BCrypt hash). The supplied `role` string is passed straight into `JwtUtils.generateToken()` and becomes the `roles` claim that every downstream RBAC check trusts. Worse, an **unrecognised** username does not fail — it falls back to `DatabaseInitializer.DEFAULT_USER_ID`, so any caller receives a valid token for the seeded owner account. A single unauthenticated POST yields an admin JWT, which defeats RBAC (`docs/SECURITY-AND-ACCESS.md` §2) and workspace isolation (§4) simultaneously. This is the most severe item in the plan.

**B2 — The JWT signing key is a committed default.**
`symphony.jwt.secret` in `application.yml` carries a working base64 HS256 key. HS256 is symmetric, so the committed value is both the signing and the verifying key: anyone with repository read access can mint tokens for any user, role, and workspace. `docs/SECRETS-MANAGEMENT.md` §4 mandates 90-day rotation with a 24-hour overlap; nothing implements rotation, and the symmetric design cannot support overlap cleanly.

**B3 — The PII firewall masks the prompt for the agent, then logs the original.**
`SupervisorController` line 62 logs `Original: '{}'` with the pre-mask query at `INFO`. The firewall's entire purpose is to keep SSNs, card numbers, and emails from leaving the trust boundary; application logs are shipped off-host, so the masking is nullified for anything log-based. `SupervisorService` line 45 logs the already-masked text and is fine.

**B4 — The secrets vault is non-durable and keyed by a committed default.**
`LocalSecretsVaultService` holds AES-256-GCM ciphertext in a `ConcurrentHashMap`. Restart drops every agent credential while the `agents.credential_vault_uri` rows pointing at them survive, leaving dangling references that surface as unexplained 401s from agents whose manifests look healthy. Its AES key is derived by SHA-256 over `symphony.vault.encryption-key`, whose default is a hardcoded development string — so even if persistence existed, the ciphertext would be readable by anyone with the repo.

**B5 — An absent `OPENAI_API_KEY` silently switches the platform into full mock mode.**
`openai.api-key` defaults to the string `mock-key`, and four classes branch on that exact value: `OpenAiEmbeddingClient` (line 45) returns seeded pseudo-random vectors, `SupervisorService` (lines 190, 202, 352, 455) returns canned parameters and bypasses outbound agent HTTP, and `GapClusteringService` (line 59) runs keyword clustering. The failure is silent and plausible-looking: semantic search returns confident rankings computed from `new Random(42)`. A deployment that merely forgets one environment variable comes up healthy and serves fabricated results.

**B6 — Health probes are skipped for any URL containing `mock`, `localhost`, or `127.0.0.1`.**
`AgentService` line 198 and `AgentHeartbeatService` line 37 both substring-match the health URL and mark the agent `ACTIVE` without issuing a request. In Kubernetes, Docker Compose, or any sidecar topology, real agents legitimately live on `localhost` — so in exactly the deployment shapes we target, the health monitor reports `ACTIVE` for agents it has never contacted. Plan 0006's entire deliverable is inert there.

### 1.2 Constraints

- **No behavioural regression for local development.** `./gradlew bootRun` with no environment variables set, and `./gradlew test`, must keep working exactly as they do today. The mock paths stay; they stop being the *default* and stop being *inferable from a missing variable*.
- **Keep the existing seams.** `SecretsVaultService` is already an interface, and `JwtUtils` already centralises token handling. Both are the right shape; this plan implements against them rather than reshaping them.
- **No new product features.** Everything in `§5 Out of scope` stays out, including the many non-blocking issues in the same audit.

### 1.3 Assumptions

- A real secret manager is reachable in the target environment. The plan implements **HashiCorp Vault** via Spring Cloud Vault, because `docs/SECRETS-MANAGEMENT.md` §1 names it first. Swapping to GCP or AWS means one more `SecretsVaultService` implementation and no other change.
- Corporate OIDC integration is **not** part of this plan. B1 is fixed by making the existing local login actually verify a password and stop trusting caller-supplied roles. Full OIDC is plan 0008.

## 2. Approach

The organising idea: **introduce an explicit mode switch, make production the strict mode, and make the application refuse to start if it is misconfigured.** Today every shortcut is reached by inference — a missing variable, a substring in a URL. Each becomes an explicit, logged, single-sourced decision.

### 2.1 Key technical decisions

- **One `symphony.mock.enabled` flag replaces all inference.** A single `MockModeProperties` bean, defaulting to `false`, is injected wherever the four classes currently compare against `"mock-key"`, and wherever the two health checkers substring-match the URL. This collapses B5 and B6 into one mechanism and removes the string `"mock-key"` from branching logic entirely. Rejected alternative: a Spring profile. Profiles compose awkwardly (`@Profile("!prod")` spreads across classes and silently changes meaning when a second profile is added), and we want the flag readable at runtime for the startup banner and the info endpoint.
- **A `ConfigurationGuard` `ApplicationRunner` fails startup on unsafe production config.** Rather than scattering validation, one component asserts the full set of invariants when `symphony.mock.enabled=false`: real OpenAI key present, JWT key material not the known default, vault key not the known default, a durable vault implementation active. It throws to abort the boot. Failing loudly at startup is the only reliable guard here, because every one of these defects is silent at runtime — that silence is what makes them dangerous.
- **JWT moves to RS256 with the private key held in the vault.** This fixes B2 properly: the verifying key becomes public, so repository access no longer implies token-minting ability, and asymmetric keys make the mandated 90-day rotation with 24-hour overlap implementable (verify against a small key set, sign with the newest). The key set is keyed by JWT `kid`. Local development generates an ephemeral keypair at boot so no key material is ever committed. Rejected alternative: keep HS256 and only move the secret to an environment variable. That addresses the committed-value symptom but leaves symmetric signing and no rotation path, so the 90-day policy stays unimplementable.
- **B1 is fixed by verifying a password and deriving the role from the database, never from the request.** Add a `BCryptPasswordEncoder` bean; `LoginRequest` gains `password` and **loses** `role`. `AuthController` resolves the user by username, verifies the password, and fails closed with 401 on either an unknown username or a mismatch — removing the default-user fallback. The JWT `roles` claim is then populated from `users.role_flag` and the workspace from the user's actual `workspace_members` row. The seeded placeholder hash is replaced with a real BCrypt hash of a development password supplied by configuration.
- **The dead header-fallback auth path is deleted.** `SupervisorController` reads `X-Workspace-Id`, `X-User-Id`, and `X-User-Role` when the security context is absent. It is currently unreachable, because `SecurityConfig` requires `.authenticated()` on `/api/v1/supervisor/**`, so a request without a valid JWT is rejected before the controller runs. But it is a complete identity-spoofing bypass sitting one `SecurityConfig` edit away from being live, and it contradicts `docs/SECURITY-AND-ACCESS.md` §2. Tests that relied on it move to minting a real test JWT.
- **B3 is fixed by removing the original query from the log statement.** The masked text is already logged one frame away in `SupervisorService`; the original adds nothing an operator needs. A `PiiRedactionTest` asserts no log output contains a known-sensitive fixture value, so the regression cannot silently return.

### 2.2 Why this ordering

Phase 1 is the configuration spine every later phase asserts against, so it lands first. Phase 2 (auth) is the most severe blocker and is independent of the vault, so it comes before the vault work rather than waiting on it. Phase 3 is a two-line privacy fix with no dependencies and ships early deliberately. Phase 4 (vault) is the largest piece of new code. Phase 5 depends on the Phase 1 flag. Phase 6 turns the guard on and updates the docs.

## 3. Phases

### Phase 1 — Mode flag and configuration guard · Status: Pending
- **Does:** Introduces `symphony.mock.enabled` (default `false`) as the single source of truth for shortcut behaviour, and a startup guard that aborts the boot when production mode is paired with development configuration.
- **Verify:** `./gradlew bootRun` with no environment variables fails to start, and the thrown message names every unsatisfied invariant. The same command with `SYMPHONY_MOCK_ENABLED=true` starts and logs a clearly marked mock-mode banner. `./gradlew test` passes with the flag enabled via test properties.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/config/MockModeProperties.java` | `@ConfigurationProperties("symphony.mock")` holding `enabled`, default `false`; injected anywhere a shortcut is taken (new) |
  | `symphony-backend/src/main/java/com/symphony/config/ConfigurationGuard.java` | `ApplicationRunner` asserting production invariants and throwing to abort startup; logs the mock-mode banner otherwise (new) |
  | `symphony-backend/src/main/resources/application.yml` | Add `symphony.mock.enabled: ${SYMPHONY_MOCK_ENABLED:false}`; remove the `:mock-key` default from `openai.api-key` so absence is an error, not a mode (modified) |
  | `symphony-backend/src/test/resources/application-test.yml` | Test properties enabling mock mode and supplying development key material, so the suite runs offline (new) |
  | `symphony-backend/src/test/java/com/symphony/ConfigurationGuardTest.java` | Asserts the guard rejects each unsafe combination and accepts a fully configured one (new) |

### Phase 2 — Authenticate the login endpoint · Status: Pending
- **Does:** Makes `/api/v1/auth/login` verify a BCrypt password, derive role and workspace from the database rather than the request, and fail closed on unknown users. Deletes the header-fallback identity path.
- **Verify:** `POST /login` with a correct password returns a token whose `roles` claim matches `users.role_flag`. The same request with a wrong password, and with an unknown username, both return 401 — and critically, neither returns a token for the default user. A request carrying `role: "ADMIN"` in the body receives a token with the database role, proving the field is ignored. A `/supervisor/chat` request bearing only `X-User-Role: ADMIN` and no JWT returns 401.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/config/SecurityConfig.java` | Add `BCryptPasswordEncoder` bean (modified) |
  | `symphony-backend/src/main/java/com/symphony/controller/AuthController.java` | `LoginRequest` gains `password`, loses `role`; verify hash, resolve role from `users.role_flag` and workspace from `workspace_members`, return 401 on unknown user or mismatch, drop the `DEFAULT_USER_ID` fallback (modified) |
  | `symphony-backend/src/main/java/com/symphony/controller/SupervisorController.java` | Delete the `X-Workspace-Id` / `X-User-Id` / `X-User-Role` fallback block; require `SymphonyAuthentication` from the security context (modified) |
  | `symphony-backend/src/main/java/com/symphony/config/DatabaseInitializer.java` | Seed a real BCrypt hash from `symphony.dev.seed-password` instead of the `"$2a$10$xyz"` placeholder; only seed when mock mode is on (modified) |
  | `symphony-backend/src/main/java/com/symphony/entity/User.java` | Add `@JsonIgnore` to `passwordHash` so it cannot surface through any serialisation path (modified) |
  | `symphony-ui/src/App.jsx` | Login form sends `password` and stops sending a chosen `role`; display the role returned by the server (modified) |
  | `symphony-backend/src/test/java/com/symphony/AuthenticationIntegrationTest.java` | Covers correct login, wrong password, unknown username, ignored role field, and header-spoofing rejection (new) |

### Phase 3 — Stop logging unmasked PII · Status: Pending
- **Does:** Removes the pre-mask query from the Supervisor log line and adds a regression test.
- **Verify:** Submit a query containing a fixture SSN and card number; assert captured log output contains neither, while the masked placeholders are still present for traceability.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/controller/SupervisorController.java` | Drop `Original: '{}'` from the line-62 log statement; log the sanitised flag and masked text only (modified) |
  | `symphony-backend/src/test/java/com/symphony/PiiRedactionTest.java` | Attaches a log appender and asserts no sensitive fixture value reaches any log record (new) |

### Phase 4 — Durable, KMS-backed secrets vault · Status: Pending
- **Does:** Adds a HashiCorp Vault implementation of the existing `SecretsVaultService` interface, selected when mock mode is off, and reconciles credential URIs that no longer resolve.
- **Verify:** Against a dev-mode Vault container, onboard an agent with a secret key, restart the application, and confirm the Supervisor still retrieves the credential and injects the `Authorization` header — the exact scenario that fails today. Confirm `LocalSecretsVaultService` is not instantiated when mock mode is off.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/build.gradle` | Add `spring-cloud-starter-vault-config` and the Spring Cloud BOM (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/HashiCorpSecretsVaultService.java` | `SecretsVaultService` implementation backed by Vault KV v2; `@ConditionalOnProperty` on mock mode being off (new) |
  | `symphony-backend/src/main/java/com/symphony/service/LocalSecretsVaultService.java` | Add `@ConditionalOnProperty` so it loads only in mock mode; log a startup warning that stored secrets are non-durable (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/AgentService.java` | On a `credential_vault_uri` that does not resolve, mark the agent `UNHEALTHY` and surface a clear message rather than failing opaquely at execution time (modified) |
  | `symphony-backend/src/main/resources/application.yml` | Vault connection properties, all environment-driven with no usable defaults (modified) |
  | `symphony-backend/src/test/java/com/symphony/SecretsVaultContractTest.java` | One contract suite run against both implementations, so they cannot drift (new) |

### Phase 5 — Honest health probes and real signing keys · Status: Pending
- **Does:** Replaces URL substring-matching with the mock flag in both health checkers, and moves JWT signing to RS256 with vault-held keys and `kid`-based rotation.
- **Verify:** With mock mode off, register an agent on a `localhost` health URL that returns 500 and confirm it is marked `UNHEALTHY` — today it is marked `ACTIVE` unprobed. Confirm a token signed with a rotated-out key still verifies inside the overlap window and is rejected after it. Confirm a token signed with the old committed HS256 secret is rejected outright.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/service/AgentService.java` | Line 198: probe unless mock mode is on; drop the `mock`/`localhost`/`127.0.0.1` substring test (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/AgentHeartbeatService.java` | Line 37: same substitution for the scheduled checker (modified) |
  | `symphony-backend/src/main/java/com/symphony/config/JwtUtils.java` | RS256 signing, `kid` header, verify against the active key set, keep a rotated-out key valid for 24h (modified) |
  | `symphony-backend/src/main/java/com/symphony/config/JwtKeyProvider.java` | Loads the RSA keypair from the vault in production; generates an ephemeral keypair at boot in mock mode (new) |
  | `symphony-backend/src/main/resources/application.yml` | Replace `symphony.jwt.secret` with vault-sourced key configuration; no committed key material remains (modified) |
  | `symphony-backend/src/test/java/com/symphony/JwtRotationTest.java` | Covers sign/verify, `kid` selection, overlap-window acceptance, post-overlap rejection, and legacy-HS256 rejection (new) |

### Phase 6 — Remove mock branching from service logic, enable the guard, update docs · Status: Pending
- **Does:** Replaces the remaining `"mock-key"` string comparisons with the injected flag, turns the guard on by default, and brings the docs into line.
- **Verify:** `grep -rn '"mock-key"' symphony-backend/src/main` returns nothing. Full suite green. With mock mode off and no OpenAI key, startup aborts rather than serving seeded-random embeddings as real results.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/service/openai/OpenAiEmbeddingClient.java` | Line 45: branch on the injected flag; keep the seeded generator for mock mode only (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/SupervisorService.java` | Lines 190, 202, 352, 455: branch on the injected flag instead of the API-key value (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/GapClusteringService.java` | Line 59: same substitution for the clustering path (modified) |
  | `docs/SECURITY-AND-ACCESS.md` | §1 implementation-status note updated: login now verifies a password and derives roles from the database; RS256 and the rotation window documented (modified) |
  | `docs/SECRETS-MANAGEMENT.md` | §3 status block updated: durable Vault-backed implementation is the production default; §4 rotation marked implemented for JWT keys (modified) |
  | `docs/ARCHITECTURE.md` | §4 stack table: secrets, auth, and LLM rows move from development shortcut to implemented (modified) |
  | `README.md` | Replace the mock-key paragraph with the explicit `SYMPHONY_MOCK_ENABLED` flag and the production variable set (modified) |

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Existing JWTs are invalidated by the HS256 → RS256 switch, logging every active session out | Expiry is 24h, so natural drainage is short. Deploy during a low-traffic window and state it in the release note. The frontend already redirects to login on a 401, so the user-visible effect is one re-login. |
| `ConfigurationGuard` blocks a deployment over a genuinely safe configuration | Every assertion names the exact property and the accepted values in its failure message. `SYMPHONY_MOCK_ENABLED=true` is the documented escape hatch, and the guard logs a loud, unmissable banner so that mode can never be mistaken for production. |
| Phase 2 locks out the seeded development user once the placeholder hash is replaced | `DatabaseInitializer` seeds a real BCrypt hash from `symphony.dev.seed-password`, documented in the README. The seeding block itself runs only in mock mode, so no development account exists in production. |
| Vault becomes a new hard startup dependency and a single point of failure | Agent credentials are already fetched per transaction, so Vault is on the request path either way. Phase 4 adds a bounded retry on the client and surfaces an unresolvable credential as an `UNHEALTHY` agent rather than an opaque execution failure. |
| Enabling real probes in Phase 5 flips many currently-`ACTIVE` agents to `UNHEALTHY` | That is the correct reading of reality, not a regression. Run the heartbeat once in a staging environment first and reconcile the resulting status list with the agent owners before production rollout. |
| Removing the header fallback breaks tests that depended on it | Phase 2 adds a test helper that mints a real JWT. Migrating the affected tests is part of that phase, not a follow-up. |

## 5. Out of scope / deferred

Deliberately excluded — each is real, none blocks deployment:

- **Full OIDC/SSO integration** (`docs/SECURITY-AND-ACCESS.md` §1). Phase 2 makes the existing login endpoint sound; replacing it with a corporate code-exchange flow is plan 0008. Separated because OIDC needs an identity-provider registration and a redirect-URI decision that are organisational, not technical.
- **PostgreSQL Row-Level Security.** Tenant isolation is currently a code-review convention. Worth hardening, but no known path crosses tenants today, so it is not a blocker.
- **The DAG `"and"` keyword heuristic**, `SupervisorService` decomposition, virtual threads, and per-agent circuit breakers. Correctness and resilience work, not deployment blockers.
- **Scheduled gap clustering**, duplicate-agent detection on onboarding, the configurable similarity threshold, agent response-schema validation, and the `authorized_roles` CSV column. Feature and data-model work.
- **PII detector quality** — Luhn validation, unformatted SSNs, `NAME` and `PHONE` detection. Phase 3 fixes only the leak of data the firewall *did* catch. Widening coverage is separate.
- **`users.email` column encryption.** Blocked on a blind-index design, because the column is `UNIQUE` and looked up by exact value; a randomised-IV converter would break both. Documented in `docs/SECRETS-MANAGEMENT.md` §3.
- **Frontend router and `App.jsx` decomposition.** Phase 2 touches the login form only.

## 6. Verification

End to end, after all phases:

1. **Negative startup:** with no environment variables, `./gradlew bootRun` aborts and names each unsatisfied invariant. This is the single most important check in the plan — it is what converts all six silent failures into loud ones.
2. **Auth:** correct password returns a token whose `roles` claim equals `users.role_flag`; wrong password and unknown username both return 401 with no token; a body-supplied `role: "ADMIN"` is ignored; a `/supervisor/chat` request with spoofed `X-User-Role` and no JWT returns 401.
3. **PII:** a query carrying a fixture SSN and card number leaves no occurrence of either in captured log output.
4. **Vault durability:** onboard an agent with a secret, restart, execute — the credential resolves and the `Authorization` header is injected.
5. **Health honesty:** a `localhost` agent returning 500 is marked `UNHEALTHY` with mock mode off, and `ACTIVE` with it on.
6. **JWT rotation:** tokens verify across the 24-hour overlap, are rejected after it, and legacy HS256 tokens are rejected outright.
7. **No mock leakage:** `grep -rn '"mock-key"' symphony-backend/src/main` is empty; `./gradlew test` is green; the existing six integration suites still pass with mock mode enabled via test properties.

## 7. Rollback

Phases are independently revertable and ordered so that reverting a later phase never breaks an earlier one.

- **Fastest mitigation without a deploy:** set `SYMPHONY_MOCK_ENABLED=true` and restart. This restores every pre-plan shortcut path. It is an emergency measure only — it disables the production guarantees this plan adds, and must be followed by a real fix.
- **Per phase:** revert that phase's commit. Phase 5's JWT change invalidates issued tokens on the way back as well as forward, so a revert costs users one re-login in each direction.
- **Phase 4 caveat:** reverting to `LocalSecretsVaultService` leaves `agents.credential_vault_uri` rows pointing into Vault that the in-memory implementation cannot resolve. Affected agents must be re-onboarded with their secrets. Capture the agent list before reverting.
- Forward-fix is preferred throughout: every change here removes a shortcut, so reverting restores a known defect.

## 8. Decision Log

| Date | Change | Why |
|---|---|---|
| 2026-10-13 | Initial draft | Six deployment blockers identified during the 2026-10-13 documentation-drift reconciliation |
| 2026-10-13 | B1 scope widened from "JWT secret is committed" to "login verifies no credential at all" | Reading `AuthController` closely showed `LoginRequest` has no password field, no `PasswordEncoder` bean exists, and an unknown username falls back to `DEFAULT_USER_ID`. The caller also supplies its own `role`, which becomes the claim every RBAC check trusts. This is an unauthenticated privilege grant, not a key-management weakness, and it is now the most severe item in the plan |
| 2026-10-13 | B3 added after the initial audit | `SupervisorController` line 62 logs the pre-mask query, nullifying the firewall for any log-shipped data. Two-line fix, so it ships early as Phase 3 rather than waiting |
| 2026-10-13 | Chose one `symphony.mock.enabled` flag over Spring profiles | Profiles compose poorly across six call sites and change meaning silently when a second profile appears. A flag is also readable at runtime for the startup banner |
| 2026-10-13 | Chose RS256 over keeping HS256 with an environment-supplied secret | Symmetric signing means the verifying key can mint tokens, and it has no clean overlap story. `docs/SECRETS-MANAGEMENT.md` §4 mandates 90-day rotation with a 24-hour overlap, which asymmetric keys plus `kid` make implementable |
| 2026-10-13 | Deleting the `X-User-*` header fallback despite it being currently unreachable | `SecurityConfig` makes it dead code today, but it is a full identity-spoofing bypass one configuration edit away from live, and it contradicts `docs/SECURITY-AND-ACCESS.md` §2 |
| 2026-10-13 | Full OIDC deferred to plan 0008 | Phase 2 makes the existing endpoint sound. OIDC additionally requires identity-provider registration and redirect-URI decisions that are organisational rather than technical, and bundling them would stall the security fix |
| 2026-10-13 | Auth (Phase 2) ordered before the vault (Phase 4) despite the vault being the larger piece | B1 is the most severe blocker and does not depend on vault work, so it should not wait behind it |

## 9. Commits / PR

- Commit/PR: _pending — plan not yet approved_
