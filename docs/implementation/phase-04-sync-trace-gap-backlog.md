# Phase 04: SyncTrace Gap Backlog and Completion Checklist

Status: Planned
Owner: SyncTrace implementation stream
Date: 2026-08-30

## Purpose
This backlog captures the remaining work after the project scan against the implementation docs and the live codebase. The goal is to convert the current SyncTrace implementation from a near-complete feature prototype into a production-ready, testable, and consistent feature set.

## Scope Summary
The feature baseline for SyncTrace is already largely present in code and docs:
- Traceability mapping CRUD
- GitHub ingestion and source tracing
- Continuity gap detection
- AI recommendations
- Teacher/student traceability views
- Audit export

The remaining work is not about adding the entire product from scratch. It is about completing the missing operational, reliability, and product-quality gaps that are still visible in the implementation.

## Evidence Sources
- [README.md](../../README.md)
- [docs/implementation/phase-01-baseline-and-gap-matrix.md](phase-01-baseline-and-gap-matrix.md)
- [docs/implementation/phase-02-backend-foundation-and-api-wiring.md](phase-02-backend-foundation-and-api-wiring.md)
- [docs/implementation/phase-03-source-ingestion-and-integration.md](phase-03-source-ingestion-and-integration.md)
- [backend/src/main/java/com/ieee/evaluator/synctrace/controller](../../backend/src/main/java/com/ieee/evaluator/synctrace/controller)
- [backend/src/main/java/com/ieee/evaluator/synctrace/service](../../backend/src/main/java/com/ieee/evaluator/synctrace/service)
- [frontend/src/synctrace](../../frontend/src/synctrace)

## Completion Checklist

### P0 — Must complete before production release
- [x] Synchronize docs and live API endpoints
  - Confirm the repository endpoints documented in the phase docs match the actual routes used in [GitHubController.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/controller/GitHubController.java).
  - Decide whether to standardize on `/api/synctrace/github/teams` and `/api/synctrace/github/ingest` or reintroduce the documented repository-specific route names.

- [x] Finalize the “Send All Results” feature for all teams
  - Resolve the current limitation in [OverviewPage.jsx](../../frontend/src/synctrace/pages/teacher/OverviewPage.jsx) where the UI intentionally refuses bulk publishing.
  - Add backend support for publishing multiple team results in a single operation if this feature is required by product scope.

- [x] Add automated test coverage for SyncTrace core flows
  - Cover continuity finding generation in [ContinuityGapDetectionService.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/service/ContinuityGapDetectionService.java).
  - Cover recommendation generation in [DiagnosticRecommendationService.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/service/DiagnosticRecommendationService.java).
  - Cover repository ingestion logic in [GitHubIngestionService.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/service/GitHubIngestionService.java).
  - Cover export generation in [AuditExportService.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/service/AuditExportService.java).

- [x] Validate real team scoping and mapping logic with live data
  - Verify that team-specific goals, components, and findings are correctly filtered in [ContinuityReadinessService.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/service/ContinuityReadinessService.java) and [useGroupOverview.js](../../frontend/src/synctrace/hooks/useGroupOverview.js).
  - Test edge cases where teams have incomplete metadata, no roster entry, or missing GitHub links.

- [x] Harden AI error-handling and fallback behavior
  - Ensure malformed or empty AI output does not break the recommendation workflow.
  - Add meaningful fallback messaging when AI providers are unavailable or return unusable JSON.
  - Confirm progress events are still reliable even when analysis fails mid-stream.

- [x] Verify the teacher/student full workflow end-to-end
  - Goal creation
  - GitHub ingestion
  - Component mapping
  - Continuity gap detection
  - Recommendation generation
  - Publishing results
  - Student result visibility
  - **Critical finding (fixed):** [TraceabilityMappingPage.jsx](../../frontend/src/synctrace/pages/teacher/TraceabilityMappingPage.jsx) used [useStagedTraceability.js](../../frontend/src/synctrace/hooks/useStagedTraceability.js), which only persisted goal-to-component mappings to `localStorage` and never called the backend `addGoalComponents` API. Continuity detection, readiness scoring, audit export, and the Results page all read from the real `GoalComponentMapping` table, so mappings created on the live Mapping page never reached them. Fixed by resolving each stage link back to its originating SmartGoal and persisting it via `addGoalComponents` on Save Mapping.

- [x] Validate report export UX with real data
  - Confirm JSON, CSV, and PDF export from [AuditExportController.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/controller/AuditExportController.java) and the frontend export flow are functional and reliable.
  - Check filenames, media types, and downloaded content for real team codes.
  - **Findings (fixed):** CSV export only included the Continuity Findings table, silently dropping goals, components, and recommendations that the JSON/PDF exports both include. JSON export was hand-built via string concatenation with incomplete escaping (control characters like tabs could produce invalid JSON). Fixed by exporting all four sections in CSV and switching JSON export to Jackson serialization via Spring's `ObjectMapper`.

### P1 — Important quality and robustness improvements
- [ ] Add repo ingestion safeguards for large and restricted repositories
  - Add better rate-limit handling, file-size caps, and skip logic for unsupported or excessively large GitHub repos.
  - Review the current assumptions in [GitHubIngestionService.java](../../backend/src/main/java/com/ieee/evaluator/synctrace/service/GitHubIngestionService.java).

- [ ] Tighten environment and configuration validation
  - Define required secrets and config checks before startup.
  - Ensure local setup and deployment config are consistent with [README.md](../../README.md) and [application.properties](../../backend/src/main/resources/application.properties).

- [ ] Improve readiness model quality
  - Confirm that readiness percentages and readiness status are based on meaningful criteria rather than only mapping coverage.
  - Validate against the real project lifecycle and teacher expectations.

- [ ] Review source provenance visibility in the UI
  - Expose source metadata clearly for implementation components ingested from GitHub and evaluation extracts.
  - Confirm provenance is visible and useful in the traceability workflow.

- [ ] Clean up API contract inconsistency between docs and code
  - Decide on the final names and contract versions for all sync-trace endpoints.
  - Add contract validation or at least a documented canonical route list.

### P2 — Polish and product completion
- [ ] Audit feature parity against README requirements
  - GitHub repository integration
  - Source code preprocessing
  - Traceability mapping
  - Continuity verification
  - AI diagnostic explanations
  - AI-generated corrective recommendations
  - Interactive traceability matrix
  - Teacher overview
  - Traceability results
  - Audit report generation and export

- [ ] Review and reduce heuristic-only logic
  - Some gaps are still based on heuristics in [gapIssues.js](../../frontend/src/synctrace/utils/gapIssues.js).
  - Confirm where backend-generated findings are authoritative and where frontend heuristics are only UI support.

- [ ] Finish documentation and developer handoff
  - Document required sample data flows and expected team codes.
  - Add a short developer runbook for local setup and debugging.

## Quick Implementation Order
1. API/doc consistency cleanup
2. Test coverage for continuity and ingestion flows
3. AI robustness and retry fallback hardening
4. Full end-to-end teacher/student workflow validation
5. Bulk publish support for all teams
6. Readiness + export validation
7. Final product polish and documentation pass

## Final Status Summary
The project is no longer at the “missing core features” stage. It is now in the “near-complete but incomplete/fragile in production” stage. The roadmap above represents the remaining work most likely required to move SyncTrace from implementation-complete to operationally trustworthy.
