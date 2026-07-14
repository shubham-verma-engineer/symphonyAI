# Implementation Plan: Advanced Multi-Agent Pipeline Chaining & DAG Executor

| Field | Value |
|---|---|
| **Plan #** | 0004 |
| **Status** | Draft |
| **Branch** | `main` |
| **Author** | Antigravity + Shubham |
| **Created** | 2026-07-14 |
| **Last updated** | 2026-07-14 |
| **Related design doc** | `docs/SYSTEM_DESIGN.md` |

## 1. Understanding

The goal is to replace the hardcoded 2-agent sequential chaining pipeline in the SymphonyAI Supervisor with a dynamic, Directed Acyclic Graph (DAG) execution engine.
- Dynamic Planning: Decompose multi-step queries into a set of dependent execution steps (`DagNode`).
- Data Flow Injection: For nodes with dependencies, dynamically map and inject the parent node's execution outputs into the input parameters of the child nodes.
- Execution Safety: Validate role-based access control (RBAC) constraints at each step of the pipeline. If any node is blocked, fail execution gracefully.
- Dual-Mode execution:
  - When OpenAI API is active (non-mock), generate the DAG structure dynamically using OpenAI Chat Completions.
  - When running locally (mock-key active), use an intelligent split-and-resolve planner that tokenizes queries and maps them to catalog search results.

## 2. Approach

- **DAG Structure**: Define inner data classes `DagNode` and `Dag` inside `SupervisorService.java` to track execution states, inputs, dependencies, outputs, and statuses.
- **LLM/Local Planner**:
  - Implement `planDag(UUID workspaceId, String query)` in `SupervisorService.java`.
  - Under `mock-key`, tokenize query clauses (e.g. by `then`, `and then`, `next`), search the catalog for the best matching agent for each clause, and link them sequentially.
  - Under production keys, request `gpt-4o-mini` to output a structured JSON DAG matching catalog agent schemas.
- **Orchestrator Execution Loop**:
  - Run an execution loop resolving nodes that are ready (all dependencies marked `COMPLETED`).
  - Map outputs of dependencies into the parameters of target nodes.
  - Execute agents via HTTP and record execution latency, status codes, and outputs.
  - Fail pipeline immediately if any step encounters RBAC denials or HTTP execution failures.

## 3. Phases

### Phase 1 — Data Structure & DAG Planner · Status: Pending
- **Does**: Add `DagNode`/`Dag` data classes and implement the LLM/Local planner logic.
- **Verify**: Compile backend and write unit tests for the planner.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/service/SupervisorService.java` | Implement DAG data structures and planDag planner methods (modified) |

### Phase 2 — Executor Engine Hooking · Status: Pending
- **Does**: Replace the hardcoded chaining block in `routeAndExecute` with the new loop executor, mapping outputs between dependencies dynamically.
- **Verify**: Run JUnit integration tests asserting multi-agent chaining outputs.
- **Changed files**:

  | File | Brief |
  |---|---|
  | `symphony-backend/src/main/java/com/symphony/service/SupervisorService.java` | Refactor routeAndExecute to run the DAG loop executor (modified) |

## 4. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Circular dependencies in LLM-generated graph | Validate that the generated graph is a valid DAG (has no cycles) before beginning execution. |
| Parameter naming mismatches during injection | Fall back to searching for generic `text`/`content` parameters if no parameter matches exactly, and inject standard prompt outputs. |

## 5. Out of scope / deferred

- Fully parallel/asynchronous thread pool execution. Nodes will be resolved sequentially according to dependency resolution constraints for simplicity and predictable transaction scopes.

## 6. Verification

- Run existing sequential chaining tests inside `GovernanceOrchestrationIntegrationTest.java` to ensure zero regressions.
- Add a new multi-agent integration test validating parameter mapping across dynamic dependency boundaries.

## 7. Rollback

- Revert the changes to `SupervisorService.java` to restore original hardcoded sequential logic.

## 8. Decision Log

| Date | Change | Why |
|---|---|---|
| 2026-07-14 | Initial draft | Proposed plan for Advanced Multi-Agent DAG Orchestrator |

## 9. Commits / PR

- Commit: TBD
