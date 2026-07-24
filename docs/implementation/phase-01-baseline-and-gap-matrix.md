# Phase 01: Baseline and Gap Matrix

Status: Completed
Owner: SyncTrace implementation stream
Date: 2026-07-18

## Purpose
This phase creates a single source of truth for what SyncTrace must deliver and what is currently implemented, partial, or missing. This gates all subsequent coding phases.

## Inputs
- Product feature targets from README.
- Existing frontend SyncTrace code paths.
- Existing backend APIs and integration services.
- Existing project docs in `docs/`.

## Requirement Gap Matrix

| ID | Required Capability | Current Status | Evidence | Phase Target |
|---|---|---|---|---|
| ST-01 | GitHub repository integration | Missing | README feature list; no SyncTrace backend endpoints yet | Phase 03 |
| ST-02 | Source code preprocessing | Missing | README feature list; no preprocessing pipeline in SyncTrace package | Phase 03 |
| ST-03 | Traceability mapping CRUD | Partial | `frontend/src/synctrace/api.js` is mock-only | Phase 02 |
| ST-04 | Continuity verification | Partial | Client-side matrix checks exist, no backend verification engine | Phase 04 |
| ST-05 | Continuity gap detection | Partial | `frontend/src/synctrace/utils/gapIssues.js` is heuristic-only | Phase 04 |
| ST-06 | AI diagnostic explanations | Partial | AI analysis exists in evaluator APIs, not wired as SyncTrace diagnostics | Phase 04 |
| ST-07 | AI corrective recommendations | Missing | No SyncTrace recommendation service/API yet | Phase 04 |
| ST-08 | Interactive traceability matrix | Partial | UI matrix exists, data source is mock/not persisted | Phase 02 |
| ST-09 | Teacher traceability dashboard | Partial | Pages exist, backend SyncTrace data contract missing | Phase 02 |
| ST-10 | Student traceability results | Partial | Student-facing evaluator reports exist, SyncTrace continuity feed missing | Phase 05 |
| ST-11 | Project readiness monitoring | Missing | No persisted readiness model per team/goal | Phase 04 |
| ST-12 | Audit report generation/export | Partial | UI actions exist, no finalized SyncTrace audit payload pipeline | Phase 05 |

## Scope Lock

### In Scope for Current Delivery Train
- Replace SyncTrace frontend mock data with real backend contracts.
- Build SyncTrace backend domain and APIs.
- Add continuity verification and gap diagnostics as backend-owned logic.
- Deliver teacher and student SyncTrace views with real continuity data.
- Deliver exportable audit report flow.

### Out of Scope for This Train
- Full UI redesign beyond SyncTrace feature completion.
- Non-SyncTrace evaluator feature rework.
- Optional webhook automation and advanced GitHub event processing beyond baseline repository ingestion.

## Acceptance Criteria for Phase Completion
- [x] All required SyncTrace features are enumerated with IDs.
- [x] Every feature has status tagged as Implemented, Partial, or Missing.
- [x] Each matrix row has code evidence or explicit absence rationale.
- [x] Future phase ownership target is assigned to every feature.
- [x] Scope boundaries are explicitly declared.

## Implementation Checklist by Workstream

### Requirements and Traceability
- [x] Build feature inventory baseline from README and docs.
- [x] Assign requirement IDs (`ST-01` through `ST-12`).
- [x] Record phase target for each requirement.

### Current-State Verification
- [x] Validate frontend SyncTrace data layer status.
- [x] Validate backend SyncTrace package readiness.
- [x] Validate reusable evaluator services relevant to SyncTrace.

### Delivery Guardrails
- [x] Lock in-scope and out-of-scope items.
- [x] Define completion criteria for this baseline phase.
- [x] Publish this baseline document for Phase 02 implementation input.

## Key Evidence References
- `README.md` (feature contract and architecture intent).
- `frontend/src/synctrace/api.js` (explicit mock implementation).
- `frontend/src/synctrace/utils/gapIssues.js` (heuristic client-side gap logic).
- `backend/src/main/java/com/ieee/evaluator/synctrace/controller/.gitkeep` (SyncTrace backend controller scaffold only).
- `backend/src/main/java/com/ieee/evaluator/controller/AiController.java` (existing evaluator AI APIs available for integration).

## Exit Decision
Phase 01 is complete. Proceed to Phase 02 (backend SyncTrace foundation + frontend API wiring replacing mocks).
