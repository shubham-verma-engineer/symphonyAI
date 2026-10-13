# Implementation Plan: Production Hardening — Ship-Blocker Remediation

| Field | Value |
|---|---|
| **Plan #** | 0007 |
| **Status** | In Progress |
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

### Phase 1 — Mode flag and configuration guard · Status: Done
- **Does:** Introduces `symphony.mock.enabled` (default `false`) as the single source of truth for shortcut behaviour, and a startup guard that aborts the boot when production mode is paired with development configuration.
- **Verify:** `./gradlew bootRun` with no environment variables fails to start, and the thrown message names every unsatisfied invariant. The same command with `SYMPHONY_MOCK_ENABLED=true` starts and logs a clearly marked mock-mode banner. `./gradlew test` passes with the flag enabled via test properties.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/config/MockModeProperties.java` | `@ConfigurationProperties("symphony.mock")` holding `enabled`, default `false` (new — landed early with Phase 2) |
  | `symphony-backend/src/main/java/com/symphony/config/ConfigurationGuard.java` | `ApplicationRunner` asserting four production invariants and throwing to abort startup; logs the mock-mode banner otherwise. Rule evaluation sits in a package-private static `findViolations` so it is testable without an application context (new) |
  | `symphony-backend/src/main/java/com/symphony/service/SecretsVaultService.java` | Adds `isDurable()`, defaulting to `false`, so an implementation must positively declare that stored secrets survive a restart (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/LocalSecretsVaultService.java` | Declares `isDurable()` as `false` explicitly rather than inheriting it, since the value gates a startup check (modified) |
  | `symphony-backend/src/main/java/com/symphony/Application.java` | Excludes `UserDetailsServiceAutoConfiguration`, which created an unreachable default account and logged a generated password at every boot (modified) |
  | `symphony-backend/src/main/resources/application.yml` | `symphony.mock.enabled` added with Phase 2. The `:mock-key` fallback on `openai.api-key` is deliberately retained — see the decision log (modified) |
  | `symphony-backend/src/test/resources/application.yml` | Test properties enabling mock mode (new — landed early with Phase 2, as `application.yml` rather than `application-test.yml`) |
  | `symphony-backend/src/test/java/com/symphony/ConfigurationGuardTest.java` | 10 tests: each invariant rejected individually, a complete configuration accepted, case and whitespace variants of `mock-key` caught, all four problems reported in one pass, and every message required to name the property to change (new) |

### Phase 2 — Authenticate the login endpoint · Status: Done
- **Does:** Makes `/api/v1/auth/login` verify a BCrypt password, derive every identity attribute from the database rather than the request, and fail closed on unknown users. Deletes the header-fallback identity path. Introduces `users.corporate_roles` so the agent-access role layer has a source of truth other than the request body.
- **Verify:** `POST /login` with a correct password returns a token whose `roles` claim matches `users.corporate_roles` and whose `platform_role` matches `users.role_flag`. Wrong password and unknown username both return 401, and neither yields a token for the default user. The `employee` account cannot obtain the `Admin` role, because no request field expresses one. A `/supervisor/chat` request with no JWT returns 401 regardless of `X-User-Role`.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/resources/db/migration/V6__add_user_corporate_roles.sql` | Adds `users.corporate_roles`; the agent-access role layer previously had no column and was taken from the request (new) |
  | `symphony-backend/src/main/java/com/symphony/config/MockModeProperties.java` | `symphony.mock.enabled`, default false. Belongs to Phase 1 but landed here because seeding gates on it (new) |
  | `symphony-backend/src/main/java/com/symphony/config/SecurityConfig.java` | Add `BCryptPasswordEncoder` bean, and an `HttpStatusEntryPoint` so an unauthenticated request to a protected path answers 401 rather than 403 (modified) |
  | `symphony-backend/src/main/java/com/symphony/controller/AuthController.java` | `LoginRequest` is now `{username, password}`; verifies the hash, resolves roles and workspace from the database, 401 on unknown user or mismatch, drops the `DEFAULT_USER_ID` fallback, compares against a dummy hash on the missing-user path so timing does not distinguish the two failures (modified) |
  | `symphony-backend/src/main/java/com/symphony/config/JwtUtils.java` | `generateToken` takes the corporate role list and the platform role instead of one caller-supplied string; emits both the `roles` and `platform_role` claims (modified) |
  | `symphony-backend/src/main/java/com/symphony/config/SymphonyAuthentication.java` | Carries the full corporate role list plus the platform role; `getRole()` kept for display and telemetry only (modified) |
  | `symphony-backend/src/main/java/com/symphony/config/JwtAuthenticationFilter.java` | Extracts every role from the `roles` claim rather than only the first, and grants one authority per role (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/SupervisorService.java` | `isRbacBlocked` intersects against the user's whole role set; `routeAndExecute` takes that set alongside the display role (modified) |
  | `symphony-backend/src/main/java/com/symphony/controller/SupervisorController.java` | Deletes the `X-Workspace-Id` / `X-User-Id` / `X-User-Role` fallback; requires `SymphonyAuthentication`; also drops the unmasked query from the log line (Phase 3's fix, applied here to avoid touching the file twice) (modified) |
  | `symphony-backend/src/main/java/com/symphony/config/DatabaseInitializer.java` | Seeds three accounts spanning both role layers with real BCrypt hashes from `symphony.dev.seed-password`; only runs in mock mode, and re-hashes on boot so pre-existing placeholder hashes are replaced (modified) |
  | `symphony-backend/src/main/java/com/symphony/entity/User.java` | `@JsonIgnore` on `passwordHash`; adds `corporateRoles` and a `getCorporateRoleList()` helper (modified) |
  | `symphony-backend/src/main/resources/application.yml` | Adds `symphony.mock.enabled` and `symphony.dev.seed-password`, the latter with no default so no usable credential is committed (modified) |
  | `symphony-backend/src/test/resources/application.yml` | Test configuration enabling mock mode and supplying a seed password, so the suite has credentials to authenticate against (new) |
  | `symphony-ui/src/App.jsx` | Replaces the role `<select>` and the automatic token fetch with a real sign-in form, a signed-out state, read-only session identity, and sign-out (modified) |
  | `symphony-ui/src/index.css` | Layout classes for the sign-in screen and the identity pill; the form reuses the existing card, form-group, form-control and button primitives (modified) |
  | `symphony-backend/src/test/java/com/symphony/AuthenticationIntegrationTest.java` | Covers correct login, wrong password, unknown username, blank credentials, no role escalation, and that no placeholder hash survives (new) |

### Phase 3 — Stop logging unmasked PII · Status: In Progress
- **Does:** Removes the pre-mask query from the Supervisor log line and adds a regression test. The log-line change itself landed with Phase 2, since both edits touch the same method; only the regression test remains.
- **Verify:** Submit a query containing a fixture SSN and card number; assert captured log output contains neither, while the masked placeholders are still present for traceability.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/controller/SupervisorController.java` | Drop `Original: '{}'` from the line-62 log statement; log the sanitised flag and masked text only (modified) |
  | `symphony-backend/src/test/java/com/symphony/PiiRedactionTest.java` | Attaches a log appender and asserts no sensitive fixture value reaches any log record (new) |

### Phase 4 — Durable, KMS-backed secrets vault · Status: Done
- **Does:** Adds a HashiCorp Vault implementation of the existing `SecretsVaultService` interface and reconciles credential URIs that no longer resolve.
- **Verify:** Against a dev-mode Vault container, onboard an agent with a secret key, restart the application, and confirm the credential still resolves — the exact scenario that fails with the in-memory store.
- **Changed files:**

  | File | Brief |
  |---|---|
  | `symphony-backend/build.gradle` | Add `spring-vault-core:3.1.2`. Deliberately not `spring-cloud-starter-vault-config`, which exists to source application configuration from Vault and brings bootstrap machinery this does not need (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/HashiCorpSecretsVaultService.java` | `SecretsVaultService` backed by a Vault KV v2 mount. Stores `vault://<mount>/<path>` so a later mount change cannot silently redirect reads, declares `isDurable() == true`, and exposes `isResolvable` so callers can distinguish an unreachable URI from a missing secret (new) |
  | `symphony-backend/src/main/java/com/symphony/config/VaultProviderConfig.java` | Selects the implementation from `symphony.vault.provider`, defaulting by mock mode; builds the `VaultTemplate`; rejects an unrecognised provider rather than falling back (new) |
  | `symphony-backend/src/main/java/com/symphony/service/LocalSecretsVaultService.java` | No longer a `@Service`; instantiated by `VaultProviderConfig`. Logs a startup warning that stored secrets are non-durable (modified) |
  | `symphony-backend/src/main/java/com/symphony/service/AgentService.java` | Reads a credential back immediately after onboarding and marks the agent `UNHEALTHY` when it cannot be resolved, instead of leaving it `ACTIVE` to fail later as an opaque 401 from the agent (modified) |
  | `symphony-backend/src/main/resources/application.yml` | Vault connection properties, all environment-driven with no usable defaults for `uri` or `token` (modified) |
  | `symphony-backend/src/test/resources/application.yml` | Pins the suite to the local provider; the contract test builds its own Vault-backed instance (modified) |
  | `symphony-backend/src/test/java/com/symphony/SecretsVaultContractTest.java` | One abstract contract satisfied by both implementations, so they cannot drift. The Vault-backed nest skips via an assumption when no Vault is reachable, which is visible in the report (new) |

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

### 6.1 Verified for Phase 2 (2026-10-13)

Run against the application on port 8091 with `SYMPHONY_MOCK_ENABLED=true` and a disposable `pgvector/pgvector:pg17` container.

| Check | Result |
|---|---|
| `POST /auth/login {"username":"attacker","role":"ADMIN"}` — the original attack | `401` |
| Unknown username with a valid password — the old default-user fallback | `401`, no token |
| Correct username, wrong password | `401` |
| `employee` logging in while injecting `role`, `platformRole` and `roles: ["Admin"]` into the body | `200` with `platform_role: USER`, `roles: ["Employee"]`, `membership_role: VIEWER` — every injected field ignored |
| `POST /supervisor/chat` with `X-User-Role: ADMIN` and no JWT | `401` |
| `employee` JWT plus spoofed `X-User-Role: HR` against an HR-only agent | `forbidden`, "Access Denied … role (Employee)" — the header is ignored |
| `shubham` (`roles: [Admin, HR]`) against the same HR-only agent | Passes RBAC on `HR`, the **second** entry in the list — the case the previous single-string check would have wrongly refused |
| Flyway history | `V1`–`V6` applied, including `add user corporate roles` |
| Seeded accounts | `employee`/USER/Employee, `hr.specialist`/CREATOR/HR, `shubham`/ADMIN/Admin,HR — all with 60-character `$2a$10$` hashes, no placeholder remaining |
| Raw SSN, card number and email in application logs after a query containing all three | 0 occurrences each; `[MASKED_SSN_1] [MASKED_CARD_1] [MASKED_EMAIL_1]` retained for traceability |
| `telemetry_logs.query_text` at rest | Base64 ciphertext |
| Test suite | 17 tests, 0 failures, 0 errors (6 new auth tests, 1 new PII test, 10 pre-existing) |
| `symphony-ui` production build | Succeeds; lint clean apart from one pre-existing `exhaustive-deps` warning |

### 6.1b Verified for Phase 1 (2026-10-13)

| Check | Result |
|---|---|
| `./gradlew bootRun` with no environment variables | Aborts with `REFUSING TO START`, listing all four unsatisfied invariants in one message |
| `SYMPHONY_MOCK_ENABLED=true` boot | Starts, logs the `MOCK MODE ENABLED` banner naming all five non-production behaviours, and serves `POST /auth/login` with `200` |
| `Using generated security password` in the startup log | 0 occurrences (was present on every boot) |
| Test suite | 27 tests, 0 failures, 0 errors (10 new guard tests on top of Phase 2's 17) |

### 6.1c Verified for Phase 4 (2026-10-13)

Against `hashicorp/vault:1.17` in dev mode on `localhost:8200`.

| Check | Result |
|---|---|
| Production-mode boot with a durable vault and non-default key material | Starts; logs `Configuration guard passed` — the first configuration that satisfies all four invariants |
| Onboard an agent carrying a `secretKey` | `ACTIVE`, `credentialVaultUri = vault://secret/symphony/agents/<uuid>/token` |
| **Kill the application, boot a fresh JVM, re-read** | Agent still `ACTIVE`, URI unchanged, and the secret reads back from Vault as `sk-survives-restart-12345`. With the in-memory store this is the step that destroyed every credential while leaving the rows behind |
| Legacy `vault:/…` URI (no mount) against the Vault provider | Reported unresolvable rather than guessed at (`legacyInMemoryUrisAreReportedUnresolvable`) |
| Unrecognised `symphony.vault.provider` | Startup fails naming the accepted values |
| Test suite | 45 tests, 0 failures, 0 errors, 0 skipped — including 18 contract tests across both implementations |

### 6.2 End to end, after all phases:

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
| 2026-10-13 | Phase 2 adds migration `V6`, contradicting this plan's "no schema change beyond one seed correction" | Removing the caller-supplied role exposed a gap the audit had missed: `users.role_flag` is constrained to `ADMIN`/`CREATOR`/`USER`, but `agents.authorized_roles` holds corporate roles such as `HR` and `Admin`. These are the two separate layers described in `docs/SECURITY-AND-ACCESS.md` section 2, and the database had a column for only the first. Deriving the `roles` claim from `role_flag` would have silently broken agent RBAC for every restricted agent, so `users.corporate_roles` was added as the missing source of truth |
| 2026-10-13 | `isRbacBlocked` widened to intersect the user's full role set, beyond Phase 2's stated scope | `corporate_roles` holds several roles per user, while the check compared one string. Left alone, a user whose matching role was not first in their list would be wrongly refused — a bug this phase would have introduced. The method is six lines and the fix is confined to it |
| 2026-10-13 | `MockModeProperties` landed in Phase 2 rather than Phase 1 | Seeding development accounts must not happen in production, so `DatabaseInitializer` needed the flag. The class is the first file of Phase 1; `ConfigurationGuard` still belongs to that phase |
| 2026-10-13 | Three seeded development accounts instead of one | The UI's role `<select>` was how RBAC was demonstrated, and it is gone. Accounts spanning both role layers (`shubham` Admin+HR, `hr.specialist` HR, `employee` Employee) keep that behaviour exercisable by signing in as different users, which is also how a real deployment behaves |
| 2026-10-13 | Phase 3's log-line fix applied during Phase 2 | Both edits land in the same method of `SupervisorController`; splitting them would have meant touching the file twice for no reviewability gain. Phase 3 retains the regression test |
| 2026-10-13 | Work committed on `feature/0007-production-hardening` rather than `main` | Earlier plans committed straight to `main`. This one changes the authentication path, so it is worth keeping reviewable in isolation; the header's Branch field was updated to match |
| 2026-10-13 | Added an `HttpStatusEntryPoint` returning 401 | End-to-end verification showed an unauthenticated request to `/supervisor/chat` was rejected with 403. The rejection was correct, but a request carrying no credentials should answer 401 so a client knows to sign in — and 401 is what this plan's verification section specifies |
| 2026-10-13 | Phase 2 verified against a disposable `pgvector/pgvector:pg17` container named `symphony-postgres` | The suite and the application both need PostgreSQL with pgvector, and none was running locally. The container is local and disposable; it is not a project dependency and is not referenced by any committed file |
| 2026-10-13 | Phase 1 keeps the `:mock-key` fallback on `openai.api-key`, which this plan had said to remove | Removing it makes the property resolve to empty, and the four service-layer branches still compare against the literal `"mock-key"` until Phase 6. Mock mode would have broken immediately. The guard instead treats both blank *and* `mock-key` as production violations, which achieves the stated goal — absence is an error, not a mode — without reaching into those services early. Phase 6 removes the literal from the YAML and the branching together |
| 2026-10-13 | `SecretsVaultService.isDurable()` added in Phase 1 rather than Phase 4 | The guard must refuse a production boot backed by a non-durable vault, so it needs a way to ask. Declared as a defaulted interface method returning `false`, so durability has to be claimed deliberately and a future in-memory double cannot pass by omission |
| 2026-10-13 | Production mode is currently unsatisfiable by design | The vault invariant cannot pass until Phase 4 lands a durable implementation, so no production configuration will start today. This is the honest state of the system — B4 is still open — and the violation message says exactly that and names the phase. `ConfigurationGuardTest` covers the passing case by supplying a durable vault directly |
| 2026-10-13 | Excluded `UserDetailsServiceAutoConfiguration` | It logged `Using generated security password: …` on every boot. The account was unreachable, since the filter chain configures neither HTTP Basic nor form login, but a reader of a security-sensitive startup log had no way to know that. Noticed in a user-supplied startup log while verifying Phase 2 |
| 2026-10-13 | Vault choice moved onto `symphony.vault.provider` instead of `@ConditionalOnProperty` keyed to mock mode | Tying the two together conflated whether the LLM is faked with where secrets live, which made the one combination needed to test durability locally — faked LLM, real Vault — impossible to express. It also spread a single choice across two files where neither named the alternative. The property defaults by mock mode, so nothing changes for anyone who does not set it, and `ConfigurationGuard` still blocks a non-durable vault in production |
| 2026-10-13 | Chose `spring-vault-core` over `spring-cloud-starter-vault-config` | The starter's purpose is sourcing application configuration from Vault, which this does not need; it would add bootstrap machinery and a Spring Cloud BOM for a `VaultTemplate` the core artefact provides directly |
| 2026-10-13 | Stored URIs carry the mount (`vault://secret/…`), unlike the in-memory form (`vault:/…`) | Recording the mount means changing `symphony.vault.kv-mount` cannot silently redirect reads for existing rows. It also makes legacy in-memory URIs identifiable rather than indistinguishable, which is what lets `AgentService` report them instead of guessing |
| 2026-10-13 | `AgentService` reads a credential back at onboarding time | Previously a credential that failed to persist surfaced only when the Supervisor dispatched and the agent answered 401 — far from the cause, and indistinguishable from the agent rejecting a valid token, while the registry still showed `ACTIVE`. Reading back at registration moves the error to the moment it is caused |
| 2026-10-13 | Production mode is now satisfiable | Verified: with a durable vault and non-default key material, the guard passes and the application starts. Phase 4 closed the last invariant that could not be met |

## 9. Commits / PR

- Commit/PR: _pending — plan not yet approved_
