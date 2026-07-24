# Phase 03: Source Ingestion and Integration

Status: Completed
Owner: SyncTrace implementation stream
Date: 2026-07-18

## Goal
Add GitHub repository linking and implementation source ingestion with preprocessing, deduplication, and provenance metadata that can be consumed by SyncTrace mapping workflows.

## Implemented Scope

### Provenance expansion
- Extended `TraceComponent` with source metadata:
  - `sourceType`
  - `sourceRef`
  - `sourceUrl`
  - `sourceHash`
  - `sourceCapturedAt`
- Preserved existing `sourceHistoryId` and extraction flags for backward compatibility.

### GitHub repository linking
- Added persistent GitHub repository link model and repository.
- Added endpoints to list and link repositories.
- Stores owner/repo/default branch and last ingestion timestamp.

### GitHub source ingestion
- Added `GitHubSourceIngestionService`:
  - Parses GitHub URLs.
  - Uses recursive tree traversal from GitHub API.
  - Fetches blob content for supported source files.
  - Applies preprocessing (comment stripping + whitespace normalization).
  - Generates SHA-256 source hash.
- Added ingestion endpoint to pull implementation components from a linked repository.

### Deduplication rules
- Added dedup check via `sourceType + sourceHash` to skip duplicate implementation components across repeated ingests.

### Evaluation extraction provenance
- Updated extraction flow to label sources as `EVALUATION_HISTORY`.
- Added source ref/hash/captured timestamp for extracted components.

## API Additions
- `GET /api/synctrace/github/repositories`
- `POST /api/synctrace/github/repositories/link`
- `POST /api/synctrace/github/repositories/{repositoryId}/ingest`

## Frontend API Module Updates
- Added helper exports in `frontend/src/synctrace/api.js`:
  - `getLinkedGitHubRepositories`
  - `linkGitHubRepository`
  - `ingestGitHubRepository`

## Validation
- Static file diagnostics (`get_errors`) returned no issues in all edited Phase 3 files.
- Frontend build completed successfully after API module updates.

## Exit Decision
Phase 03 is complete. Stop here before Phase 04 as requested.
