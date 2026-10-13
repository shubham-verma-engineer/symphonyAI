# SymphonyAI — Security, SSO, and Governance Specification

This document details the security and governance architecture of SymphonyAI, including SSO integrations, Role-Based Access Control (RBAC) boundaries, and the PII Masking Firewall Proxy.

---

## 1. Single Sign-On (SSO) & Session Authentication

SymphonyAI enforces centralized authentication via corporate Single Sign-On (SSO) using OpenID Connect (OIDC) or OAuth 2.0.

> **Implementation status**: the OIDC redirect and code-exchange flow below is the target design and is **not yet wired up**. Today `POST /api/v1/auth/login` authenticates a local username and password against the `users` table and issues the platform JWT directly (JJWT, HS256, signed with `symphony.jwt.secret`, 24h expiry). `JwtAuthenticationFilter` then validates that token on every request and populates a `SymphonyAuthentication`. The JWT shape and all downstream RBAC logic are already built against the payload shown below, so swapping the credential check for an OIDC code exchange does not disturb the rest of the chain.

```mermaid
sequenceDiagram
    autonumber
    actor User as Employee
    participant Client as Web App
    participant Auth as Identity Provider (SSO)
    participant API as Gateway Backend

    User->>Client: Click Login
    Client->>Auth: Redirect to corporate OIDC login
    Auth->>User: Display credentials screen
    User->>Auth: Provide credentials (MFA verified)
    Auth-->>Client: Return Authorization Code
    Client->>API: Exchange code for Platform Session JWT
    API->>API: Validate scopes and verify workspace assignments
    API-->>Client: Return JWT Session Token (expires 24 hrs)
```

### Session JWT Payload Example
```json
{
  "iss": "symphony-ai.internal",
  "sub": "usr_90f23b2e88a0",
  "name": "Jane Doe",
  "email": "jane.doe@enterprise.com",
  "roles": ["HR_ADMIN", "REGIONAL_MANAGER"],
  "assigned_workspaces": [
    {
      "workspace_id": "ws_d8923a102e3b",
      "membership_role": "EDITOR"
    }
  ],
  "exp": 1783584000
}
```

---

## 2. Authorization & RBAC Matrix

SymphonyAI separates permissions into two distinct layers:
1. **Workspace Scope**: Defines what operations a user can perform *within the platform registry and telemetry views*.
2. **Agent Access Scope**: Defines if a user's corporate SSO identity roles grant permission to *route payloads to a specific registered agent*.

### 2.1 Workspace Membership Role Boundaries
* **OWNER**: Full administrative control. Can add/remove members, update workspace billing/settings, register or delete any agent, and view all token costs and audit trails.
* **EDITOR**: Technical onboarding role. Can upload/update agent manifests, register new endpoints, and inspect execution telemetry for their own agents.
* **VIEWER**: End-user employee role. Can search the marketplace and initiate conversations with the Supervisor router, but cannot modify manifests, view administrative telemetry, or change settings.

### 2.2 Agent-Level Access Validation Flow
When the Supervisor routes a query to an agent whose manifest sets `security.requiresRBAC = true`, the PII/RBAC Firewall intercepts:
1. The firewall extracts the user's `roles` array from their session JWT.
2. The firewall evaluates the user's roles against the agent's registered `authorizedRoles` array.
3. If an intersection exists (e.g. `roles` contains `"HR_ADMIN"` and `authorizedRoles` allows `"HR_ADMIN"`), the request is forwarded.
4. If no matching role is found, the firewall drops the request and returns a `403 Forbidden` response. The Supervisor translates this to a polite refusal in the chat interface.

---

## 3. PII & Data Protection Firewall

To protect employee privacy and keep proprietary data secure, the platform intercepts prompt payloads sent to third-party or developer-hosted agent endpoints.

```mermaid
graph TD
    A[Raw Prompt from User] --> B[Firewall Intercept]
    B --> C{Scan for PII?}
    C -->|Yes| D[NER & Regex Engine]
    D --> E[Store Real Values in Session Cache]
    D --> F[Generate Masked Payload]
    C -->|No| G[Forward Original Payload]
    F --> H[Post Masked Payload to Developer Agent]
    H --> I[Receive Agent Response]
    I --> J[Unmask Response using Session Cache]
    J --> K[Return Safe Output to User]
```

### 3.1 Masking Algorithm
1. **Entity Scan**: identifies sensitive spans in the prompt.
   * **Implemented today** — `PiiFirewallService` runs three regex patterns only, emitting `[MASKED_EMAIL_n]`, `[MASKED_CARD_n]`, and `[MASKED_SSN_n]` placeholders:

     | Entity | Pattern basis |
     | :--- | :--- |
     | `EMAIL` | `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}` |
     | `CREDIT_CARD` | 13–16 digits with optional spaces/hyphens; **no Luhn check, so long digit runs such as order or invoice numbers are masked as false positives** |
     | `SSN` | `\d{3}-\d{2}-\d{4}`; **misses unformatted 9-digit SSNs** |

   * **Planned** — a Named Entity Recognition model to additionally cover `NAME`, `PHONE`, `BANK_ACCOUNT`, and `IP_ADDRESS`. Regex alone cannot catch `NAME`, so the person names in the §3.2 example below are **not** masked by the current implementation.
2. **Tokenization**: Replaces matching text strings with unique, serialized tokens:
   * *"Send salary sheet of Jane Doe to hr-agent@enterprise.com"* $\rightarrow$ *"Send salary sheet of `[MASKED_NAME_01]` to `[MASKED_EMAIL_01]`"*.
3. **Session Cache**: Stores the mapping `{ "[MASKED_NAME_01]": "Jane Doe", "[MASKED_EMAIL_01]": "hr-agent@enterprise.com" }` in an encrypted Redis session cache with a Time-To-Live (TTL) of 15 minutes.
   * **Implementation status**: Redis is not yet a dependency. `PiiFirewallService.sanitize()` returns the token map inside its `FirewallResult`, and `SupervisorService` holds it in local scope for the life of the single request, discarding it when the call returns. This is adequate for one-shot routing but will not survive multi-turn conversations or a horizontally scaled gateway — both of which need the shared, TTL'd store described above.
4. **Response Detokenization**: Before rendering the response back to the user, the PII Proxy maps any occurring `[MASKED_XX]` tags back to their raw cached strings.

---

## 4. Multi-Tenant Hard Isolation

To prevent information leaks between separate organizational entities or business groups:
* Workspace isolation is enforced in the repository layer today: `workspace_id` is threaded from the JWT through the service calls into every agent, telemetry, failed-query, and gap lookup. It is a **convention enforced by code review**, not by the database — PostgreSQL Row-Level Security is not enabled, so a future query that omits the predicate would silently cross tenants. Enabling RLS with a per-request `SET LOCAL` tenant parameter would turn this into a guarantee.
* All database queries verifying or retrieving agents, telemetry, or logs MUST include a hard-coded `workspace_id` predicate:
  ```sql
  SELECT * FROM agents WHERE id = ? AND workspace_id = ?;
  ```
* Attempting to query or execute an agent belonging to a different workspace yields a `404 Not Found` error rather than a `403 Forbidden` error, ensuring the platform does not leak the existence of unauthorized resources.
