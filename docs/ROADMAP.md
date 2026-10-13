# SymphonyAI — Phased Implementation Roadmap

This document outlines the strategic implementation phases, deliverables, and timelines for the SymphonyAI platform.

---

## Roadmap Timeline at a Glance

```
┌───────────────────────────────────────┐
│ Phase 1: Unified Directory & Search   │ (Weeks 1 - 4)
│ - Creator onboarding UI               │
│ - agent-manifest.json registration    │
│ - Semantic search engine (pgvector)   │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│ Phase 2: Routing & Telemetry Logging  │ (Weeks 5 - 12)
│ - Supervisor NLP intent routing       │
│ - Live telemetry logging              │
│ - Innovation Gap analytics dashboard   │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│ Phase 3: Deep Orchestration & Gov     │ (Weeks 13+)
│ - Multi-agent sequential chaining    │
│ - RBAC policy & PII masking firewall  │
│ - Token cost accounting dashboards    │
└───────────────────────────────────────┘
```

---

> **Delivery status as of 2026-10-13.** All three phases have landed in some form on `main`; the per-plan breakdown lives in `docs/implementation-plans/README.md` (plans 0001–0006, all Done). What remains is production hardening rather than net-new phases:
>
> | Phase | Status | Outstanding |
> | :--- | :--- | :--- |
> | Phase 1 — Directory & Discovery | Delivered | Creator CLI never built; `/health` probe is bypassed for `localhost`/`mock` URLs |
> | Phase 2 — Routing & Telemetry | Delivered | Gap clustering runs in-process and on-demand, not as a cron worker; no scheduled clustering job |
> | Phase 3 — Orchestration & Governance | Delivered | SSO is local login, not OIDC; PII store is request-scoped, not Redis; secrets vault is in-memory, not KMS-backed |

## 1. Phase 1: The Unified Directory & Discovery UI (Weeks 1 - 4)

* **Objective**: Catalog all employee-built AI tools in a standardized internal registry, reducing discovery friction.

### Key Milestones & Deliverables
* **Marketplace UI**: Search and browse directory interface showing agent categories, creator contact, capabilities, and health status.
* **Onboarding UI**: Portal for creators to upload their `agent-manifest.json` and credentials.
* **Registry Service**: Validates JSON schema rules, runs an async loopback check (`GET /health`) against the agent endpoint, and stores metadata in PostgreSQL.
* **Semantic Search Engine**: Generates embeddings from agent descriptions via `text-embedding-3-small` and stores vectors in `pgvector` with HNSW indices.
* **Validation Tests**: Unit test suites validating schema matching and verifying vector distances.

---

## 2. Phase 2: Automated Routing & Telemetry Logging (Weeks 5 - 12)

* **Objective**: Introduce conversational NLP routing and log unaddressed user requirements to generate an R&D backlog.

### Key Milestones & Deliverables
* **The Supervisor Chat Interface**: Conversation box for end-users to state their business problem.
* **Intent-Based Router**: An LLM router that parses natural language and executes function-calling to route the prompt payload to a single matching agent.
* **Parameter Mapper**: Extract variables from prompt context and inject them into the target agent's API parameters automatically.
* **Telemetry Dashboard**: Tracks total runs, response times, token costs, and average latency per agent.
* **Failed Query Logging**: Inserts user prompts that yield no matching agents or result in session dropouts into the `failed_queries` table.
* **Gap Clustering Pipeline**: A cron job that runs a clustering pipeline (e.g. TF-IDF + K-Means or LLM clustering) on failed queries to identify capability gaps.
* **Live Demand Dashboard**: Renders the priority backlog of unmet capabilities for creators.

---

## 3. Phase 3: Deep Orchestration & Complete Governance (Weeks 13+)

* **Objective**: Automate multi-step pipelines and enforce enterprise-grade security controls.

### Key Milestones & Deliverables
* **Multi-Agent Pipeline Chaining**: Orchestrates sequences of agents (e.g. running Parser Agent, extracting fields, and sending them as inputs to Translator Agent) using workflow trees.
* **SSO/OIDC Integration**: Integrates authentication filters mapping corporate identities to platform JWT session tokens.
* **RBAC Enforcement**: Evaluates user SSO roles against the target agent's `authorizedRoles` rule before dispatching calls.
* **PII Masking Firewall**: Implements regex and NER filters to sanitize prompts, storing real values in a Redis session cache, and detokenizing final agent outputs.
* **Secrets Vault Integration**: Stores developer agent authentication tokens in HashiCorp Vault.
* **Token Cost Allocation**: Automatically attributes LLM token expenditures and infrastructure costs back to departments based on user SSO profile flags.
