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
  - `sourceCapturedAt`
- Preserved existing `sourceHistoryId` and extraction flags for backward compatibility.

### GitHub repository linking
- Added team-scoped repository links with role labels, ingestion status, file count, last-ingested time, and failure reason.
- Added link validation against GitHub, duplicate detection, role updates, and soft unlinking.
- Unlinking retains implementation components and mappings while marking their source as removed.
- Student operations are scoped to the student's own team.

### GitHub source ingestion
- Added `GitHubIngestionService`:
  - Parses GitHub URLs.
  - Uses recursive tree traversal from GitHub API.
  - Fetches blob content for supported source files.
  - Applies preprocessing (comment stripping + whitespace normalization).
- Repository ingestion supports one repository or all linked repositories, with independent status and retry actions.
- Repository-level failures roll back component changes so the last successful source snapshot remains available.
- Empty repositories with no supported source files return a clear validation error.
- Added ingestion endpoint to pull implementation components from a linked repository.

### Deduplication rules
- Repeated ingests update the existing team/file-path implementation component instead of adding a duplicate.

### Evaluation extraction provenance
- Updated extraction flow to label sources as `EVALUATION_HISTORY`.
- Added source ref/hash/captured timestamp for extracted components.

## API Additions
- `GET /api/synctrace/github/repositories?teamCode=...`
- `POST /api/synctrace/github/repositories` (also accepts the legacy `/link` path)
- `PATCH /api/synctrace/github/repositories/{repositoryId}`
- `GET /api/synctrace/github/repositories/{repositoryId}/impact`
- `DELETE /api/synctrace/github/repositories/{repositoryId}`
- `POST /api/synctrace/github/repositories/{repositoryId}/ingest`
- `POST /api/synctrace/github/ingest` (single repository, all linked repositories, or legacy URL ingestion)

## Frontend API Module Updates
- Added helper exports in `frontend/src/synctrace/api.js`:
  - `getLinkedGitHubRepositories`
  - `linkGitHubRepository`
  - `updateGitHubRepositoryRole`
  - `unlinkGitHubRepository`
  - `ingestGitHubRepository`

## Validation
- Static file diagnostics (`get_errors`) returned no issues in all edited Phase 3 files.
- Frontend build completed successfully after API module updates.

## Exit Decision
Phase 03 is complete. Stop here before Phase 04 as requested.
