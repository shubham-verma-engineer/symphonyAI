# SymphonyAI — Frontend UI/UX Specification

This document details the interface components, page layouts, route mappings, and design systems for the SymphonyAI platform.

---

## 1. Design System & Aesthetics

SymphonyAI adheres to a premium, modern design language:
* **Core Theme**: Support for Dark Mode and Light Mode with HSL CSS variables.
* **Typography**: Clean, professional layout using **Inter** or **Outfit** fonts.
* **Styling**: Vanilla CSS structure utilizing CSS Grid and Flexbox with glassmorphic cards (semi-transparent backgrounds with backdrop filters) and micro-interactions.
* **Visual Polish**: Smooth animations on state transitions (e.g. registry pings, live chat streaming, dashboard expansions).

---

## 2. Route Architecture

### 2.1 Target Routes

```
/                         -> Marketplace Portal (Browse/Search)
/onboard                  -> Creator Dashboard (Register Agent / Upload Manifest)
/chat                     -> End-User Interface (Supervisor Chat & Routing Visuals)
/admin/gaps               -> Admin Leaderboard (Clustered Gaps & Telemetry)
/admin/billing            -> Admin Cost & Token Accounting Dashboard
```

### 2.2 As Implemented Today

No client-side router is wired up yet. `symphony-ui/src/App.jsx` is a single component that switches on a `view` state value, so none of the screens has its own URL — no deep links, no browser back/forward, no refresh-safe state.

| Target route | `view` value | Screen heading |
| :--- | :--- | :--- |
| `/` | `marketplace` | Internal Registry |
| `/onboard` | `onboard` | Onboard Agent |
| `/chat` | `chat` | Supervisor Chat |
| `/admin/gaps` | `analytics` | Demand Backlog |
| `/admin/billing` | `billing` | Token Billing & Allocation |

Adopting the target routes means adding a router and splitting `App.jsx` (currently ~980 lines) into per-route components.

---

## 3. Detailed Page Layouts & Components

### 3.1 Marketplace Portal (`/`)
An "App Store" style layout where users search for existing enterprise agents.

```
┌────────────────────────────────────────────────────────────────────────┐
│  SymphonyAI  [Search input: e.g. "I want to parse a PDF"]    [Profile] │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  Browse by Category: [Legal] [HR] [Finance] [Developer Tools] [All]    │
│                                                                        │
│  ┌────────────────────────┐  ┌────────────────────────┐                │
│  │ Legal Contract Agent  │  │ Resume Parser          │                │
│  │ Runs: 1.2k | Lat: 42ms │  │ Runs: 800 | Lat: 12ms  │                │
│  │ [ ACTIVE ] [ DETAILS ] │  │ [ ACTIVE ] [ DETAILS ] │                │
│  └────────────────────────┘  └────────────────────────┘                │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```
* **Search Bar**: Semantically maps queries to agents, listing matched cards with similarity scores.
* **Agent Card**: Displays agent name, description, version, health status badge, owner, and vital telemetry metrics (runs, latency, success rate).

### 3.2 Creator Console (`/onboard`)
The onboarding center for internal developers to register their manifest schemas.

* **Upload Panel**: A drag-and-drop zone accepting `.json` manifest uploads.
* **Input Fields**: Fallback manual inputs for name, description, API URLs, and authentication keys (injected into Vault).
* **Validation Live Terminal**: An interactive terminal window displaying verification steps:
  * `[PASS]` Manifest format checks.
  * `[PASS]` JSON schema validation.
  * `[PING]` Requesting test execution from `/health` endpoint.
  * `[PASS]` Vector embedding generated.
  * `[DONE]` Agent registered (assigned uuid).

### 3.3 Supervisor Conversation Console (`/chat`)
The interface for user interaction and dynamic agent execution.

* **Left Panel - Chat History**: Collapsible list of active chat threads.
* **Main Area - Chat Stream**: Conversational output showing user prompts and Supervisor responses.
* **Right Panel - Execution Inspector**: A collapsible visual inspect drawer displaying:
  * **Intent Routing Log**: *"Supervisor identified user intent as CONTRACT_EVALUATION. Routing to 'legal-contract-analyzer'."*
  * **Parameter Extraction**: `documentUrl: "https://files.internal/contracts/contract_04.pdf"`, `strictMode: true`.
  * **PII Filter Log**: *"PII data masked: Client name replaced by [MASKED_NAME_01]"*.
  * **Network Status**: HTTP 200, 240ms.

### 3.4 Live Demand & Gap Dashboard (`/admin/gaps`)
The corporate analytics interface highlighting organizational requirements that are unaddressed.

* **Top Capability Gaps Leaderboard**: A table of clustered queries, ranked by frequency.
  * **Example Row**: Title: *"Jira ticket auto-creator"* | Frequency: *"142 searches/week"* | Details: *"Users attempting to automate Jira ticket generation from chat logs."*
  * **Developer Action**: A "Build Solution" button that pre-populates a manifest scaffolding with the required description, parameters, and intent vectors.
* **Telemetry Charts**: Interactive charts displaying workspace costs, API failures, and daily active user trends.
