# SymphonyAI — High-Level Architecture Spec

This document details the high-level architecture, system blocks, data-flow pipelines, and tech-stack mapping for the SymphonyAI platform.

---

## 1. System Block Diagram

The following architecture diagram represents the core subsystems of SymphonyAI and how they interact:

```mermaid
flowchart TB
    subgraph ClientLayer [Client Layer]
        UI[Marketplace & Supervisor Web UI]
        CLI[Creator CLI Tool]
    end

    subgraph AppLayer [SymphonyAI Central Gateway]
        API[API Gateway & Router]
        SSO[SSO / Auth Filter]
        Firewall[PII Masking Firewall]
        Registry[Registry Service]
        Orchestrator[Supervisor Orchestrator]
        GapEngine[Gap Clustering Engine]
    end

    subgraph DataLayer [Data Layer]
        Postgres[(PostgreSQL Registry DB)]
        VectorDB[(Vector DB / pgvector)]
        Vault[(Secure Secrets Vault)]
    end

    subgraph AgentLayer [External Developer Agent Endpoints]
        AgentA[HR Query Agent]
        AgentB[Legal Document Parser]
        AgentC[General Utility Agent]
    end

    %% Client to App Interactions
    UI -->|HTTPS / WSS| API
    CLI -->|HTTPS / Auth| API
    
    %% Gateway Filters
    API --> SSO
    SSO --> Registry
    SSO --> Orchestrator
    
    %% DB & Vault Read/Writes
    Registry -->|Write Agent Metadata| Postgres
    Registry -->|Embed & Index Descriptions| VectorDB
    Orchestrator -->|Read Active Agents| Postgres
    Orchestrator -->|Vector Similarity Query| VectorDB
    API -->|Fetch Agent Access Keys| Vault
    
    %% Routing to Agents
    Orchestrator --> Firewall
    Firewall -->|HTTPS Payload| AgentA
    Firewall -->|HTTPS Payload| AgentB
    Firewall -->|HTTPS Payload| AgentC

    %% Gap Telemetry
    Orchestrator -->|Log Failed Queries| Postgres
    GapEngine -->|Cluster Failed Queries| Postgres
```

---

## 2. Core Subsystems

### 2.1 The Smart Registry Service
Manages onboarding and lifecycle events for custom developer agents. It parses the uploaded `agent-manifest.json`, validates its JSON schema constraints, runs a loopback `/health` check against the agent's endpoint, and indexes the semantic definition (e.g. system instructions, description) in pgvector.

### 2.2 The Supervisor Orchestrator
The central NLP router. It converts user queries into vector embeddings, performs similarity lookups in pgvector to find candidate agents, and runs an LLM tool-calling logic step to determine:
1. **Single-Agent Routing**: Directly formatting and dispatching the payload to the matching agent.
2. **Multi-Agent Pipeline Chaining**: Sequencing execution (e.g. running Agent A, mapping its output parameters, and feeding them to Agent B).
3. **No-Match Refusal**: Logging the query as unaddressed and returning a graceful refusal to the user.

### 2.3 The PII & RBAC Firewall Proxy
Acts as a security filter shielding developer agent endpoints:
* **RBAC checking**: Validates user JWT claims and Single Sign-On (SSO) attributes against the registry rules for the target agent.
* **PII Masking**: Sanitizes the prompt (identifying credit card numbers, SSNs, phone numbers, and names) and swaps them with masked tokens before forwarding the request.

### 2.4 The Telemetry & Gap Detector Engine
A background service that processes telemetry. It reads unaddressed or failed query logs from PostgreSQL, processes them using a lightweight text-clustering pipeline (e.g. TF-IDF + K-Means or LLM summarization), and groups them into prioritized feature gaps.

---

## 3. Data Flow Sequences

### 3.1 Agent Onboarding Flow
```mermaid
sequenceDiagram
    autonumber
    actor Creator as Developer (Creator)
    participant UI as Onboarding Portal
    participant Registry as Registry Service
    participant Vault as Secrets Vault
    participant VDB as Vector DB

    Creator->>UI: Upload agent-manifest.json & credentials
    UI->>Registry: Submit Manifest Payload
    Registry->>Registry: Validate Schema Constraints
    Registry->>Registry: Send /health ping to Agent endpoint
    Registry-->>UI: Fail if unhealthy / invalid
    Registry->>Vault: Store API Auth Credentials for Agent
    Registry->>VDB: Generate embedding & index Agent description
    Registry->>Registry: Save metadata to PostgreSQL (Status: ACTIVE)
    Registry-->>UI: Onboarding Success (Agent ID generated)
```

### 3.2 Conversational Routing & Execution Flow
```mermaid
sequenceDiagram
    autonumber
    actor User as End-User (Employee)
    participant UI as Chat Interface
    participant Gateway as Central Gateway
    participant Orchestrator as Supervisor Orchestrator
    participant VDB as Vector DB
    participant Firewall as PII/RBAC Firewall
    participant Agent as Target Developer Agent

    User->>UI: Input: "Find all pending legal contracts for client X"
    UI->>Gateway: Send request with User JWT (SSO)
    Gateway->>Orchestrator: Parse conversational context
    Orchestrator->>VDB: Query matching agents (Vector Similarity)
    VDB-->>Orchestrator: Return Candidate Agents (e.g. Legal Agent)
    Orchestrator->>Firewall: Verify User RBAC claims for Legal Agent
    Firewall-->>Orchestrator: Authorized
    Orchestrator->>Firewall: Request PII sanitization of prompt
    Firewall->>Firewall: Scrub Client X details, swap with [MASKED_01]
    Firewall->>Agent: POST payload to Legal Agent endpoint
    Agent-->>Firewall: Return JSON result (e.g. contract list)
    Firewall->>Firewall: Unmask [MASKED_01] -> Client X in results
    Firewall-->>Orchestrator: Sanitized Result
    Orchestrator-->>UI: Display results with grounded citations
    UI-->>User: View Contract List
```

---

## 4. Technology Stack Mapping

* **Backend Services**: Java / Spring Boot (Spring WebFlux for streaming responses and high concurrency, Spring Security for SSO/OAuth).
* **Primary Relational DB**: PostgreSQL (retains user tables, agent metadata registry tables, logs, and telemetry).
* **Vector DB Engine**: pgvector extension on PostgreSQL (cos-similarity matching on agent manifests).
* **Secrets Storage**: HashiCorp Vault or Google Secret Manager (storing API keys for external developer agents).
* **LLM Engine**: OpenAI API (`gpt-4o-mini` for intent routing/chaining decisions, `text-embedding-3-small` for search embeddings).
