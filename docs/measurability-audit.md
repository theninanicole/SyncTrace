# SyncTrace Measurability Audit

**Scope:** whether SyncTrace records the data needed to compute validation outcomes M1–M11 from exported session records.
**Method:** read-only review of the code on `main` at `bd4741e`. No code was changed.
**Date:** 2026-11-19

---

## 0. Codebase map (as relevant to measurement)

| Area | What exists |
|---|---|
| Stack | Spring Boot (Java) backend in `backend/`, React + Vite frontend in `frontend/`, PostgreSQL on Supabase (`application.properties:6-11`), deployed via `render.yaml` (backend) and `frontend/vercel.json`. |
| ORM / schema | JPA/Hibernate with `spring.jpa.hibernate.ddl-auto=update` (`application.properties:14`). Tables are generated from entities, so new tables or columns appear on the next boot without a migration tool. No explicit naming strategy is set, so Spring's default applies: camelCase becomes snake_case, including explicit names such as `@Column(name = "goalId")` → `goal_id`. Confirm with `\d <table>` before running the queries in §4. |
| SyncTrace entities | `backend/src/main/java/com/ieee/evaluator/synctrace/model/`: `SmartGoal`, `TraceComponent`, `StagedTraceMapping`, `GoalComponentMapping`, `TraceabilityMappingActivity`, `MappingSuggestion`, `MappingGenerationRun`, `ContinuityFinding`, `DiagnosticRecommendation`, `ContinuityAnalysisRun`, `GitHubRepositoryLink`. |
| Base evaluator entities | `backend/.../model/`: `EvaluationHistory` (IEEE Docs Evaluator reports, the source for component retrieval), `ViewedReport`, and others. |
| API routes | `/api/synctrace/proposals`, `/goals`, `/components`, `/staged-mappings`, `/mapping-suggestions`, `/github`, `/continuity`, `/audit` (all under `synctrace/controller/`). |
| Background jobs | None. Every operation runs synchronously inside the HTTP request. There is no `@Scheduled` or `@Async` code and no queue. |
| AI-call wrapper | `AiProvider.complete(prompt)` → `OpenAiProvider` (`service/OpenAiProvider.java:87`). HTTP timeouts are 60 s to connect and 300 s to read (`config/AiHttpClientConfig.java:16-17`). There is no response caching. Each service carries its own retry loop (3 attempts, 180 s budget). |
| Progress reporting | `ProgressEmitter` (`service/ProgressEmitter.java:27`) keeps SSE emitters in an in-memory `ConcurrentHashMap`. Events go to the browser and are not persisted. |
| Export | `GET /api/synctrace/audit/{teamCode}/export?format=json\|csv\|pdf` (`AuditExportController.java:28`). It exports the team's traceability state and contains no run timings. |
| Logging / analytics | SLF4J via Lombok `@Slf4j` with no `logback` config, so logs go to stdout only (Render's log stream). No Actuator, Micrometer or any analytics SDK (searched `pom.xml`, `frontend/src`, `frontend/index.html`). |
| Timing code | The only persisted duration in the codebase is `MappingGenerationRun.durationMs` (`MappingSuggestionService.java:189`). Every other `System.currentTimeMillis()` in SyncTrace is a retry-budget timer that is never saved (`ProposalAnalysisService.java:98`, `DiagnosticRecommendationService.java:139`, `SourceCodeAlignmentService.java:107`). The frontend has no `performance.now()` or duration code. Its `Date.now()` calls only build session IDs. |
| Session-record export | **Not found.** There is no admin page, script, or endpoint that exports per-session or per-run records. Searched for `session record`, `export`, `admin`, `metrics` and `report` across `backend/src/main` and `frontend/src`. |

---

## 1. Summary table

| ID | Outcome | Status | Where it's recorded | Gap |
|----|---------|--------|---------------------|-----|
| M1 | SMART Goal extraction run time ≤ 30 s | **Not measurable** | Nothing persisted. `/extract-goals` returns drafts only (`ProposalAnalysisController.java:28-46`, `ProposalAnalysisService.java:163-165`). `smart_goals.created_at` is written later, at review/save time. | No start, end or duration for an extraction run. The draft is never saved, so the run leaves no trace at all. |
| M2 | Component retrieval run time ≤ 30 s | **Not measurable** | Nothing persisted. `trace_components.created_at` / `source_captured_at` are set per component during parsing (`TraceComponentController.java:389-390`). Re-runs reuse existing rows (`TraceComponentService.java:275-298`). | No run record. Re-extraction updates no timestamps. Team is only derivable indirectly from `evaluation_history.file_name`. |
| M3 | Repo preprocessing run time ≤ 30 s per repo | **Partially measurable** | `github_repository_links.ingestion_status`, `ingested_file_count`, `last_ingested_at`, `last_ingest_error` (`GitHubRepositoryLinkService.java:177-197`). Proxy: `MIN/MAX(trace_components.source_captured_at)` per repo (`GitHubIngestionService.java:287, 306`). | No start time (`updated_at` is overwritten on completion). Only the **last** run survives. The proxy excludes branch lookup and tree fetch, and failed runs have no duration. |
| M4 | Mapping stage finalization time ≤ 15 min per stage | **Partially measurable** (wall-clock proxy only) | `staged_trace_mappings.created_at` per link (`StagedMappingService.java:109`). `traceability_mapping_activity.performed_at` + `stage` on Save Mapping (`StagedMappingController.java:92-105`). | There is no "stage opened" or "stage finalized" event and no active-time tracking. `stage` on the activity row is the stage selected at save, but the save covers the whole chain. Removed links are hard-deleted. |
| M5 | AI-suggested link acceptance ≥ 70 % | **Partially measurable** | `mapping_suggestions.status` ∈ PENDING/ACCEPTED/EDITED/REJECTED, plus `decided_by` and `decided_at` (`MappingSuggestion.java:19-26`). `staged_trace_mappings.origin` (`StagedTraceMapping.java:43-51`). | REJECTED **is** kept. However, **Discard** and **regenerate** hard-delete PENDING suggestions (`MappingSuggestionService.java:182-183, 283-288`), so those never reach the denominator. Stale-target auto-rejections are stored as REJECTED (`:390-396`), and Accept All can't be told apart from single accepts. |
| M6 | Continuity analysis run time ≤ 60 s | **Not measurable** | `continuity_analysis_runs.last_analyzed_at`: one row per team, overwritten (`ContinuityAnalysisRun.java:13-21`, `ContinuityGapDetectionService.java:83-88`). | A single end-ish timestamp with no start time and no history. It times only the deterministic detection step, not the AI recommendation or alignment steps. |
| M7 | Gap explanation coverage = 100 % | **Partially measurable** (current state only) | `diagnostic_recommendations.root_cause` (explanation) + `recommendation` (corrective), keyed by `finding_id` (`DiagnosticRecommendation.java:16-27`). | Coverage is computable by LEFT JOIN, but only for the latest run, because findings and recommendations are deleted and rebuilt on each run. Blank texts are saved as "covered". Findings the AI skips are dropped silently. |
| M8 | Audit report export time ≤ 10 s | **Not measurable** | Nothing persisted (`AuditExportController.java:28-64`, `AuditExportService.java:89-102`). | No export log, duration or format record. |
| M9 | Readiness summary per team, Provisional excluded | **Partially measurable** | Computed on request by `ContinuityReadinessService.getTeamReadinessSummary` (`:52-143`) and embedded in each audit export (`AuditExportService.java:258`). | Never stored, and reproducible only from current state. **No "Provisional" concept exists anywhere** (`grep -ri provisional` returns 0 hits), so every finding is penalised (`:125, 165-170`). |
| M10 | Source-code alignment per SDD component (Aligned / Partial / Not Found), by repo | **Not measurable** | `continuity_findings` rows with `doc_type_from=SDD`, `doc_type_to=IMPLEMENTATION`, free-text `description` (`SourceCodeAlignmentService.java:237-244`). | The AI is asked only for misalignment descriptions (`:208-211`). There is no per-component status and no component or repository ID on the finding, and aligned components are never recorded. |
| M11 | Teacher monitoring/review time ≤ 10 min per group | **Not measurable (confirmed)** | Nothing. `viewed_reports` (`model/ViewedReport.java:10-28`) only records a group's first view of an evaluator report. It has no user and no end time, and it isn't a SyncTrace page. | No teacher session, page-view or dwell-time tracking. |

---

## 2. Evidence per outcome

### M1: SMART Goal extraction run time

**Code path:** `POST /api/synctrace/proposals/extract-goals` → `ProposalAnalysisService.extractSmartGoals` → `extractWithRetry` → `extractOnce` (Drive download + text extraction + `provider.complete`).

`ProposalAnalysisController.java:28-46`:
```java
/** Extracts a draft goal tree for review. Nothing is saved until /save-goals. */
@PostMapping("/extract-goals")
...
List<Map<String, Object>> goals = proposalAnalysisService.extractSmartGoals(
    fileId, fileName, model, sessionId, teamCode);
return ResponseEntity.ok(Map.of("goals", goals, "count", goals.size()));
```

`ProposalAnalysisService.java:98-130`: the only timer feeds the retry budget and is discarded:
```java
long startedAt = System.currentTimeMillis();
...
long elapsed = System.currentTimeMillis() - startedAt;
if (elapsed + delayMs > timeLimitMs) { throw ... }
```

`ProposalAnalysisService.java:145-160`: the work a duration should cover:
```java
docsService.extractDocumentContent(fileId, MAX_PAGES_TO_RENDER, sessionId, progressEmitter);
...
String response = provider.complete(prompt);
...
return parseGoalDrafts(response);
```

- **Duration / timestamps:** none. `smart_goals.created_at` (`SmartGoal.java:35`) is set by `/save-goals` after the user reviews the drafts, so it measures review time and cannot give the extraction duration.
- **Tied to team/user/run:** team is resolved (`ProposalAnalysisController.java:37`) but never stored for the run. The user isn't recorded, and the run has no ID beyond the SSE `sessionId`.
- **Persistence:** none. SSE progress events (`RECEIVED` → `COMPLETE`) are in-memory only. One `log.warn` per failed attempt goes to stdout (`:119`).
- **Retries / failures:** up to 3 attempts with jittered backoff and a 180 s budget (`:26-30, 91-139`). Non-retryable errors (permission, not found, no text) fail fast (`:122-128`). Concurrent duplicates return 409 (`:66-69`). None of these outcomes is recorded.

### M2: Component retrieval run time (from IEEE Docs Evaluator reports)

**Code path:** `POST /api/synctrace/components/extract?sessionId=` → `TraceComponentController.extractTraceComponents` (`:207-249`) → `extractComponentsFromEvaluation` (text parsing of `evaluation_history.evaluation_result`) → `TraceComponentService.persistExtractedComponents`.

`TraceComponentController.java:218-243`:
```java
EvaluationHistory history = historyRepository.findById(historyId)...
String evaluationResult = history.getEvaluationResult();
...
List<TraceComponent> extractedComponents = extractComponentsFromEvaluation(
    evaluationResult, historyId, history.getExtractedImages());
...
List<TraceComponent> savedComponents = componentService.persistExtractedComponents(...);
return ResponseEntity.ok(Map.of("components", savedComponents, "count", savedComponents.size()));
```

- **No AI call.** Retrieval parses the "Diagram Analysis" section of an existing evaluator report (`:257-344`). The evaluator's own AI analysis is a separate earlier run whose completion time is `evaluation_history.evaluated_at` (`service/AiService.java:358`). That run is not part of M2.
- **Timestamps:** each new component gets `source_captured_at` and `created_at = now()` (`TraceComponentController.java:389-390`, `TraceComponentService.java:308-310`). On a re-run, `matchPreviousExtraction` reuses the existing rows (`TraceComponentService.java:275-298`) and doesn't touch their timestamps, so a repeat run leaves no trace.
- **Team:** `trace_components` has no team column. Team is derived from `evaluation_history.file_name` via `TeamComponentResolverService.resolveTeamCode` (`:78-91`).
- **Persistence:** none for the run. Progress is SSE only.
- **Failures:** returned as HTTP 500 with no record (`:244-248`).

### M3: GitHub repository preprocessing run time

**Code path:** `POST /api/synctrace/github/repositories/{id}/ingest` or `POST /github/ingest` → `GitHubRepositoryLinkService.ingest` (`:175-203`) → `GitHubIngestionService.ingestRepository` (`:63-91`) → `ingestRepositoryInternal` (tree fetch + per-file fetch).

`GitHubRepositoryLinkService.java:177-197`:
```java
repository.setIngestionStatus("INGESTING");
repository.setLastIngestError(null);
repositoryLinkRepository.save(repository);          // start: only updated_at changes
...
repository.setIngestionStatus("INGESTED");
repository.setIngestedFileCount(components.size());
repository.setLastIngestedAt(LocalDateTime.now());  // end
repositoryLinkRepository.save(repository);          // updated_at overwritten again
...
} catch (Exception e) {
    repository.setIngestionStatus("FAILED");
    repository.setLastIngestError(messageFor(e));
```

`GitHubRepositoryLink.java:82-85` overwrites `updated_at` on every save, so the "INGESTING" moment is lost:
```java
@PreUpdate
public void onUpdate() { updatedAt = LocalDateTime.now(); }
```

`GitHubIngestionService.java:268, 287, 306`: every file is stamped as it is processed. This gives a recoverable lower-bound window for the last run:
```java
String sourceRef = owner + "/" + repo + "@" + branch + ":" + path;
...
found.setSourceCapturedAt(LocalDateTime.now());      // existing row
...
component.setSourceCapturedAt(LocalDateTime.now());  // new row
```

- **Tied to repo and team:** yes. `github_repository_links.id`, `team_code`, `owner`, `repo`.
- **History:** only the last run per repository. There is no per-run table.
- **Unlinked path:** `GitHubController.ingestUnlinkedRepository` (`:234-250`) updates `last_ingested_at` only if a matching active link exists.
- **Whole operation?** The proxy window misses branch resolution and tree fetch (`GitHubIngestionService.java:104-117`) and the time to the first file.
- **Retries / failures:** GitHub rate-limit retries with 250 ms × n pauses (`:356-397`). Hitting the rate limit mid-run stops early but still returns success (`:183-197`), so the repo is marked INGESTED with fewer files. Failed runs record only status and error, with no duration.

### M4: Mapping stage finalization time

**Stages:** `StagedMappingService.java:28-34` defines exactly the five stages in the validation plan (`PROPOSAL_SRS`, `SRS_SDD`, `SRS_STD`, `SRS_SPMP`, `SDD_IMPLEMENTATION`).

**Per-link creation** (`StagedMappingService.java:101-112`):
```java
row.setCreatedBy(createdBy);
row.setCreatedAt(LocalDateTime.now());
row.setOrigin(origin);
```

**Removal is a hard delete** (`StagedMappingService.java:120-127`): `stagedRepository.delete(row);`

**Save Mapping activity** (`StagedMappingController.java:92-105`):
```java
StagedMappingService.SyncResult result = stagedMappingService.sync(teamCode);
// ... a save that changed nothing must not record one.
if (result.added() + result.removed() == 0) return result;
TraceabilityMappingActivity activity = new TraceabilityMappingActivity();
activity.setTeamCode(teamCode);
activity.setStage(stage);
activity.setPerformedBy(accessGuard.currentUserEmail());
activity.setMappingCount(result.linkCount());
activity.setPerformedAt(LocalDateTime.now());
```

- **Stage attribution is unreliable.** `sync` publishes the whole chain across all stages (`StagedMappingService.java:129-135`), but `stage` is whichever stage was selected when Save was clicked (`frontend/src/synctrace/hooks/useStagedTraceability.js:631`). The delete path records `stage = null` (`StagedMappingController.java:73`). Saves that change nothing are not recorded.
- **No start or finalize event.** A search for `finaliz|locked|stageComplete|markComplete` found no stage-finalization concept. The frontend has no timers (no `performance.now`/`Date.now` in the mapping hooks).
- **Best available proxy:** for each team and stage, `MIN(staged_trace_mappings.created_at)` to the last `traceability_mapping_activity.performed_at` with that stage. This is wall-clock time. It starts at the first link rather than at stage open, includes idle time, and shifts if early links are removed.
- **Legacy path:** `SmartGoalController.java:189-195` also writes activity rows for direct goal↔component links.

### M5: AI-suggested link acceptance

**Model** (`MappingSuggestion.java:19-26`):
```java
private Status status = Status.PENDING;
@Column(name = "run_id") private String runId;
private String decidedBy;
private LocalDateTime decidedAt;
public enum Status { PENDING, ACCEPTED, EDITED, REJECTED }
```

**Rejections are kept** (`MappingSuggestionService.java:226-231`):
```java
if ("REJECT".equals(action)) {
    suggestion.setStatus(MappingSuggestion.Status.REJECTED);
    suggestion.setDecidedBy(user);
    suggestion.setDecidedAt(LocalDateTime.now());
    suggestionRepository.save(suggestion);
```

**Undecided suggestions are hard-deleted** on Discard and on every regeneration:
```java
// MappingSuggestionService.java:182-183 (inside persistGeneratedRows, i.e. every new run)
suggestionRepository.deletePendingByTeamAndStage(teamCode, stageKey, MappingSuggestion.Status.PENDING);

// MappingSuggestionService.java:283-288
public void discard(String teamCode, String stage) {
    suggestionRepository.deletePendingByTeamAndStage(teamCode, stage, MappingSuggestion.Status.PENDING);
}
```
`MappingSuggestionRepository.java:14-17` defines it as a JPQL `delete`.

**System-caused rejections are stored as user rejections** (`MappingSuggestionService.java:390-396`):
```java
suggestion.setStatus(MappingSuggestion.Status.REJECTED);
suggestion.setReason("Target component no longer exists.");
```

**Other details:**
- Accept All loops over `decide(... "ACCEPT" ...)` (`:265-281`). The result is indistinguishable from individual accepts.
- An EDIT with N targets creates N `staged_trace_mappings` rows with `origin = AI_EDITED` (`:249-256`), so count suggestions, not staged rows.
- If the pair is already staged, `addInternal` keeps the existing row's origin (`StagedMappingService.java:101-113`).
- Previously rejected pairs are filtered out of later runs (`:95-98, 361`). This is good for UX, but it means later runs are smaller.
- **`mapping_generation_runs` (bonus timing data).** `duration_ms` covers source loading, every AI batch and the DB save (`:85, 189`). However, `status` is hard-coded `"COMPLETE"` (`:191`). Partial runs (`failed > 0`, `:158`) and failed runs (`:159-164`) are not recorded, and the table has no `run_id` column linking it to `mapping_suggestions.run_id`.

**What you can compute today:** (ACCEPTED + EDITED) / (ACCEPTED + EDITED + REJECTED) from `mapping_suggestions`. The pending suggestions that were discarded or superseded, and so never decided, are missing from that denominator.

### M6: Continuity analysis run time

**What one "run" is.** On the Results page, `useAiContinuityAnalysis.js:63-67` calls `POST /continuity/detect-gaps` (deterministic, no AI) and then `POST /continuity/recommendations` (AI). Source-code alignment (`POST /continuity/align`, AI) runs separately from `SourceCodePage.jsx:324`.

`ContinuityGapDetectionService.java:72, 83-88`:
```java
LocalDateTime detectedAt = LocalDateTime.now();   // taken AFTER clearing old findings, BEFORE evaluating
...
run.setLastAnalyzedAt(detectedAt);
analysisRunRepository.save(run);
```

`ContinuityAnalysisRun.java:13-21`: `team_code` is the primary key, so there is one row per team and it is overwritten on each run.

- **Duration:** none. `last_analyzed_at` is a single point in time and doesn't even bound the detection step.
- **AI portion:** `DiagnosticRecommendationService` (`:139`) and `SourceCodeAlignmentService` (`:107`) time only their retry budgets.
- **Persistence:** history is lost on every run.
- **Retries:** 3 attempts and 180 s per AI step. The provider read timeout is 300 s.

### M7: Gap explanation coverage

**Model** (`DiagnosticRecommendation.java:16-27`): `finding_id`, `root_cause` (diagnostic explanation), `recommendation` (corrective action), `priority`, `created_at`. One row per finding is intended.

**Generation** (`DiagnosticRecommendationService.java:194-198, 252-274`):
```java
for (ContinuityFinding finding : findings) {
    if (finding.getId() != null) recommendationRepository.deleteByFindingId(finding.getId());
}
...
if (findingIndex < 0 || findingIndex >= findings.size()) continue;   // AI index out of range → skipped
...
if (!covered.add(finding.getId())) continue;                          // duplicate → skipped
String rootCause = cleanDiagnosticText(node.path("rootCause").asText());          // "" if missing
String recommendationText = cleanDiagnosticText(node.path("recommendation").asText());
```

- **Coverage is computable now** for the current state: findings LEFT JOIN recommendations, with non-blank `root_cause` and `recommendation`.
- **Coverage is never checked or recorded.** If the AI returns fewer objects than there are findings, the missing ones get no row and no error. Empty strings are saved and would count as covered under a naive join. `ContinuityReadinessService` reports `findingsWithRecommendations` (`:115-117, 138`) using row presence only.
- **History:** gap detection deletes the previous goal findings and their recommendations (`ContinuityGapDetectionService.java:119-123`). Alignment does the same for its own findings (`SourceCodeAlignmentService.java:249-259`). Recommendations are deleted before the AI call, so if every retry fails, the team is left with findings and no recommendations.
- **Transactions:** `@Transactional` on private methods (`DiagnosticRecommendationService.java:182, 241`; `SourceCodeAlignmentService.java:150, 216`) has no effect under Spring's proxy model, so these steps are not atomic.

### M8: Audit report export time

`AuditExportController.java:28-55`:
```java
@GetMapping("/{teamCode}/export")
public ResponseEntity<?> exportAuditReport(@PathVariable String teamCode,
        @RequestParam(defaultValue = "json") String format) {
    ...
    byte[] reportData = auditExportService.exportAuditReport(effectiveTeamCode, normalizedFormat);
    ...
    return ResponseEntity.ok().headers(headers).body(reportData);
```
`AuditExportService.java:89-102` builds the report (it recomputes readiness live, `:258`) and renders JSON, CSV or PDF.

- **Duration, format, user, team:** none recorded. No log line on success.
- **Frontend:** `frontend/src/synctrace/api.js:435` downloads the file with no timing.

### M9: Readiness summary

`ContinuityReadinessService.java:122-128, 165-170`:
```java
int findingsPenalty = Math.min(MAX_FINDINGS_PENALTY, computeFindingsPenalty(severityCounts));
int readinessScore = totalGoals == 0 ? 0
    : Math.max(0, Math.min(100, averageCoveragePercent - findingsPenalty));
...
return severityCounts.getOrDefault("CRITICAL", 0) * PENALTY_CRITICAL
    + severityCounts.getOrDefault("HIGH", 0) * PENALTY_HIGH ...
```

- **Stored?** No. It is computed per request at `GET /continuity/summary/{teamCode}` and embedded in each audit export. An exported JSON or CSV is therefore a point-in-time snapshot, but only if someone exports it.
- **Reproducible?** Only from current state. Once mappings or findings change, past values can't be recovered.
- **Provisional exclusion:** **not implemented.** No field, enum value or filter named or meaning "provisional" exists in the backend or frontend. Every finding in `continuity_findings` is penalised by severity, including AI-generated SDD→Implementation alignment findings (`goal_id IS NULL`).

### M10: Source-code alignment results

`SourceCodeAlignmentService.java:208-211` (prompt contract):
```java
sb.append("- \"description\": A clear description of the misalignment\n");
sb.append("- \"severity\": One of \"LOW\", \"MEDIUM\", \"HIGH\", or \"CRITICAL\"\n");
```
`:237-244` (what is stored):
```java
finding.setTeamCode(teamCode);
finding.setDocTypeFrom(DocType.SDD);
finding.setDocTypeTo(DocType.IMPLEMENTATION);
finding.setSeverity(severity);
finding.setDescription(description);
```

- There is no per-SDD-component status, no SDD component ID, and no matched implementation or repository ID. Aligned components produce nothing. The prompt sends names only, not IDs (`:193-205`).
- A search for `Aligned`, `PARTIAL` and `Not Found in Code` across backend and frontend found no matches. The frontend doesn't derive these statuses either.
- **Repository grouping:** IMPLEMENTATION components carry `source_ref = owner/repo@branch:path` (`GitHubIngestionService.java:268`), so the code side can be grouped by repo. However, components are deduplicated by name `"{teamCode} - {path}"` (`:260, 265`). Two repositories of the same team with the same path (for example `README.md` or `src/main.js`) share one row, and the later ingestion overwrites the other repo's `source_ref`.

### M11: Teacher monitoring/review time

- **Confirmed not recorded.** The backend has no login, session or page-view logging (authentication is stateless Supabase JWT, `security/JwtAuthenticationFilter.java`). The frontend has no analytics.
- `viewed_reports` (`model/ViewedReport.java:10-28`) is unique on (`group_code`, `report_id`) and `viewed_at` is `updatable = false`. It marks a group's first view of an evaluator report and has no user, role or duration.
- Incidental teacher traces exist only when a teacher writes something: `staged_trace_mappings.created_by`, `mapping_suggestions.decided_by`, `traceability_mapping_activity.performed_by` (emails). Review is mostly reading, so these don't bound a review session.

---

## 3. Proposed minimal changes (not implemented)

### A. One run-log table for M1, M2, M3, M6, M7, M8, plus M5's generation runs. Effort: **M** overall (S per hook)

New entity `SyncTraceRunLog` → table `synctrace_run_log` (created automatically by `ddl-auto=update`):

| Column | Type | Notes |
|---|---|---|
| `id` | bigserial | |
| `run_id` | uuid | One per server operation |
| `client_session_id` | varchar | The existing SSE `sessionId`. It groups the steps of one user action. |
| `operation` | varchar(40) | `GOAL_EXTRACTION`, `COMPONENT_RETRIEVAL`, `REPO_INGESTION`, `GAP_DETECTION`, `RECOMMENDATIONS`, `ALIGNMENT`, `AUDIT_EXPORT`, `MAPPING_SUGGESTION` |
| `team_code`, `user_email`, `user_role` | varchar | From `SyncTraceAccessGuard` |
| `repository_id`, `stage`, `format`, `source_ref` | nullable | Repo (M3), stage (M5), export format (M8), file ID or history ID (M1/M2) |
| `started_at`, `ended_at` | timestamptz | Use `Instant`/`OffsetDateTime` to avoid server-TZ ambiguity |
| `duration_ms` | bigint | Measured with `System.nanoTime()` |
| `ai_ms`, `external_ms` | bigint | Time inside `provider.complete` and inside GitHub/Drive calls |
| `attempts` | int | Retry count |
| `status` | varchar(20) | `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`, `CONFLICT` |
| `item_count`, `uncovered_count` | int | Goals, components, files, findings or suggestions; findings without a full explanation (M7) |
| `model` | varchar | AI model in use (settings can change it between sessions) |
| `error` | text | |

Add a small `RunLogService.time(operation, context, Supplier)` that wraps the body in `try/finally`, so failures and 409s are logged too. Write the log row in its own transaction (`REQUIRES_NEW`) so a rollback in the operation doesn't erase it.

Hook points:

| Outcome | Hook |
|---|---|
| M1 | Wrap the body of `ProposalAnalysisService.extractSmartGoals` (`:60-86`). Time `docsService.extractDocumentContent` and `provider.complete` separately inside `extractOnce` (`:146-156`). |
| M2 | Wrap `TraceComponentController.extractTraceComponents` (`:207-249`). Resolve team via `TeamCodeResolver.extractTeamCode(history.getFileName())`. |
| M3 | Wrap `GitHubRepositoryLinkService.ingest` (`:175-203`) with `repository_id`. Also wrap `GitHubController.ingestUnlinkedRepository` (`:234`). Set `status=PARTIAL` when the rate limit stopped ingestion early (`GitHubIngestionService.java:193`) or the tree was truncated (`:123`). |
| M6 | Wrap `ContinuityGapDetectionService.detectGaps` (`:49`), `DiagnosticRecommendationService.generateRecommendations` (`:71`) and `SourceCodeAlignmentService.analyzeAlignment` (`:59`). Pass the frontend's `sessionId` to `/detect-gaps` too (it doesn't send one today, `useAiContinuityAnalysis.js:63`). Pipeline time = `max(ended_at) − min(started_at)` per `client_session_id`. |
| M7 | In `DiagnosticRecommendationService.parseAndCreateRecommendations` (`:242-282`), count findings with no row or with blank `root_cause`/`recommendation`, and write `uncovered_count`. Optionally re-prompt once for only the uncovered findings. |
| M8 | Wrap `AuditExportService.exportAuditReport` (`:89-102`) with `format`. Also record `item_count` = byte size. |
| M5 runs | Write to the same table from `MappingSuggestionService.generate` (`:83-168`), including failure and partial paths. Alternatively, fix `mapping_generation_runs` by adding `run_id`, a real `status`, `sources_processed` and `sources_total`, and logging on failure (S). |

### B. M5 denominator. Effort: **S**

1. Add statuses `DISCARDED` (user clicked Discard), `SUPERSEDED` (replaced by regeneration) and `STALE` (target deleted) to `MappingSuggestion.Status` (`MappingSuggestion.java:26`).
2. Replace `deletePendingByTeamAndStage` (`MappingSuggestionRepository.java:14-17`) with an `update ... set status = :newStatus, decidedAt = now` query. Use DISCARDED in `discard` (`MappingSuggestionService.java:283-288`) and SUPERSEDED in `persistGeneratedRows` (`:182`).
3. Use `STALE` in `markStale` (`:390-396`) instead of `REJECTED`.
4. Add `decision_method varchar(20)` (`SINGLE`, `ACCEPT_ALL`) set in `decide` and `acceptAll` (`:265-281`).
5. Decide, and write in the validation plan, whether DISCARDED counts as rejection. Recommended: report both "explicit" (ACCEPTED + EDITED) / (ACCEPTED + EDITED + REJECTED) and "strict" (… + DISCARDED + SUPERSEDED).

### C. M4 stage timing. Effort: **M**

1. New table `mapping_stage_event` (`team_code`, `stage`, `user_email`, `event`, `at`, `client_session_id`) with events `STAGE_OPEN`, `STAGE_HIDDEN`, `STAGE_VISIBLE`, `LINK_ADDED`, `LINK_REMOVED`, `SAVE`.
2. Frontend: in `useStagedTraceability.js`, post `STAGE_OPEN` when `selectedStage` changes and on mount, and `STAGE_HIDDEN`/`STAGE_VISIBLE` on `visibilitychange`, plus an idle timeout (e.g. no input for 2 min = hidden). Use `navigator.sendBeacon` on `pagehide`. Endpoint: `POST /api/synctrace/staged-mappings/events`.
3. Backend: write `LINK_ADDED` and `LINK_REMOVED` in `StagedMappingService.addInternal` (`:101-113`) and `remove` (`:120-127`). Removal then leaves a trace, so the stage start no longer moves when early links are deleted.
4. Fix the activity-row stage: have `sync` return the set of stages that actually changed and record one activity row per changed stage (`StagedMappingController.java:92-105`). Effort S.
5. Define "finalization" in the plan. Recommended: the last `SAVE` for a stage within the session. Then **active time** = sum of visible, non-idle intervals between `STAGE_OPEN` and that `SAVE`, and **wall-clock** = `SAVE − STAGE_OPEN`. Report both.

### D. M9 Provisional and snapshots. Effort: **M** (needs a definition first)

1. **Define "Provisional"** in the plan. It does not exist in code. Candidates: findings from AI alignment (`goal_id IS NULL`), findings on components not yet confirmed (`MappingSuggestionService.java:317-318` notes a planned "Needs Review" flag), or findings detected after the last mapping save.
2. Add `provisional boolean default false` to `continuity_findings` and set it where findings are created (`ContinuityGapDetectionService.toFinding` `:126`, `SourceCodeAlignmentService` `:237`).
3. Skip provisional findings in `computeFindingsPenalty` (`ContinuityReadinessService.java:111-114, 165`). Report them separately as `provisionalCount`.
4. New table `readiness_snapshot` (`team_code`, `computed_at`, `run_id`, `readiness_score`, `average_coverage_percent`, `findings_penalty`, `total_findings`, `provisional_findings`, `status`, `summary_json`). Write it at the end of each continuity pipeline run and on each audit export.

### E. M10 per-component alignment. Effort: **L**

1. Change the alignment prompt (`SourceCodeAlignmentService.java:185-214`) to send component IDs and require one object per SDD component: `{ sddComponentId, status: "ALIGNED"|"PARTIAL"|"NOT_FOUND", matchedImplementationIds: [...], explanation }`.
2. New table `source_alignment_result` (`run_id`, `team_code`, `sdd_component_id`, `status`, `repository_id`, `matched_implementation_ids`, `explanation`, `analyzed_at`). Derive `repository_id` from the matched components' `source_ref` owner/repo → `github_repository_links`. Keep creating `continuity_findings` for PARTIAL and NOT_FOUND so readiness and recommendations still work.
3. Validate that every SDD component got exactly one status, and record the rest as `UNASSESSED` so the coverage of the alignment itself is visible.
4. Fix the cross-repo name collision. Deduplicate IMPLEMENTATION components by `source_ref` (owner/repo + path) instead of `"{team} - {path}"` (`GitHubIngestionService.java:260-266`). Effort S, but it changes existing rows, so do it before validation data is collected.
5. Surface statuses on the Source Code page.

### F. M11 teacher review time. Effort: **S** (manual) / **M** (instrumented)

- **Recommended for this validation:** time teacher reviews manually (observer stopwatch, or screen recording with start = teacher opens the group, end = teacher states a verdict). Record the result in the validation sheet. This avoids building tracking just for one study.
- **If instrumented:** reuse `mapping_stage_event`-style events for teacher pages (`TEACHER_REVIEW_OPEN` / `HIDDEN` / `VISIBLE` / `CLOSE` with `team_code`), fired from the teacher Results, Mapping and Source Code pages when a team is selected.

### G. Snapshot/history safety net. Effort: **S**

Until A–E land, findings, recommendations, readiness and repo status are overwritten on every run. Add a teacher-only `GET /api/synctrace/admin/session-records` (see §4) or run the §4 SQL **immediately after each team's session** so data isn't lost.

---

## 4. Export plan

### 4a. What can be pulled today (no code changes)

Run in the Supabase SQL editor, or with `psql` using `-v team=TEAMCODE -v from='2026-12-01' -v to='2026-12-02'`, and `\copy (...) to 'm5.csv' csv header` for each query.

```sql
-- M3 (proxy, LAST run per repo only): file-stamp window, excludes tree fetch
SELECT l.id AS repository_id, l.owner || '/' || l.repo AS repository, l.team_code,
       l.ingestion_status, l.ingested_file_count, l.last_ingested_at, l.last_ingest_error,
       MIN(c.source_captured_at) AS first_file_at, MAX(c.source_captured_at) AS last_file_at,
       EXTRACT(EPOCH FROM MAX(c.source_captured_at) - MIN(c.source_captured_at)) AS approx_seconds
FROM github_repository_links l
LEFT JOIN trace_components c
       ON c.doc_type = 'IMPLEMENTATION' AND c.source_type = 'GITHUB'
      AND split_part(c.source_ref, '@', 1) = l.owner || '/' || l.repo
      AND c.source_removed IS NOT TRUE
WHERE lower(l.team_code) = lower(:'team') AND l.active
GROUP BY l.id;

-- M4 (proxy, wall-clock): first surviving link → last save tagged with that stage
SELECT s.stage,
       MIN(s.created_at) AS first_link_at,
       (SELECT MAX(a.performed_at) FROM traceability_mapping_activity a
         WHERE lower(a.team_code) = lower(:'team') AND a.stage = s.stage) AS last_save_at,
       COUNT(*) AS links,
       COUNT(*) FILTER (WHERE s.origin = 'MANUAL')      AS manual_links,
       COUNT(*) FILTER (WHERE s.origin = 'AI_ACCEPTED') AS ai_accepted_links,
       COUNT(*) FILTER (WHERE s.origin = 'AI_EDITED')   AS ai_edited_links
FROM staged_trace_mappings s
WHERE lower(s.team_code) = lower(:'team')
GROUP BY s.stage ORDER BY s.stage;

-- M5: suggestion decisions (discarded/superseded PENDING rows are already gone)
SELECT stage,
       COUNT(*) FILTER (WHERE status = 'ACCEPTED') AS accepted,
       COUNT(*) FILTER (WHERE status = 'EDITED')   AS edited,
       COUNT(*) FILTER (WHERE status = 'REJECTED' AND reason <> 'Target component no longer exists.') AS rejected,
       COUNT(*) FILTER (WHERE status = 'REJECTED' AND reason =  'Target component no longer exists.') AS stale,
       COUNT(*) FILTER (WHERE status = 'PENDING')  AS still_pending,
       ROUND(100.0 * COUNT(*) FILTER (WHERE status IN ('ACCEPTED','EDITED'))
             / NULLIF(COUNT(*) FILTER (WHERE status IN ('ACCEPTED','EDITED')
                     OR (status = 'REJECTED' AND reason <> 'Target component no longer exists.')), 0), 1)
         AS acceptance_pct
FROM mapping_suggestions
WHERE lower(team_code) = lower(:'team')
  AND created_at BETWEEN :'from' AND :'to'
GROUP BY ROLLUP(stage);

-- M5 bonus: suggestion generation run times (successful runs only)
SELECT stage, created_at, duration_ms, suggestion_count, status
FROM mapping_generation_runs WHERE lower(team_code) = lower(:'team') ORDER BY created_at;

-- M6 (end-point only, no duration)
SELECT team_code, last_analyzed_at FROM continuity_analysis_runs WHERE lower(team_code) = lower(:'team');

-- M7: coverage for the CURRENT findings
SELECT f.id AS finding_id, f.goal_id, f.goal_id IS NULL AS is_alignment_finding,
       f.doc_type_from, f.doc_type_to, f.severity, f.detected_at,
       (r.id IS NOT NULL AND btrim(coalesce(r.root_cause, ''))     <> '') AS has_explanation,
       (r.id IS NOT NULL AND btrim(coalesce(r.recommendation, '')) <> '') AS has_recommendation
FROM continuity_findings f
LEFT JOIN diagnostic_recommendations r ON r.finding_id = f.id
WHERE lower(f.team_code) = lower(:'team');
```

- **M9 today:** call `GET /api/synctrace/continuity/summary/{teamCode}` or export the audit JSON right after the session. Neither excludes Provisional findings, because that concept doesn't exist yet.
- **M1, M2, M6 duration, M8, M10, M11:** nothing to export.

### 4b. After changes A–E: one query per team

```sql
-- M1, M2, M3, M6 (per step), M8, M5-generation: per-run timings
SELECT operation, team_code, user_email, user_role, repository_id, stage, format,
       client_session_id, started_at, ended_at, duration_ms, ai_ms, external_ms,
       attempts, status, item_count, uncovered_count, model, error
FROM synctrace_run_log
WHERE lower(team_code) = lower(:'team') AND started_at BETWEEN :'from' AND :'to'
ORDER BY started_at;

-- M6 pipeline (detect + recommendations, grouped by the client session)
SELECT client_session_id, MIN(started_at) AS started_at, MAX(ended_at) AS ended_at,
       EXTRACT(EPOCH FROM MAX(ended_at) - MIN(started_at)) * 1000 AS pipeline_ms,
       bool_and(status = 'SUCCESS') AS all_succeeded
FROM synctrace_run_log
WHERE lower(team_code) = lower(:'team') AND operation IN ('GAP_DETECTION','RECOMMENDATIONS')
  AND started_at BETWEEN :'from' AND :'to'
GROUP BY client_session_id;

-- M4 active vs wall-clock time per stage: computed from mapping_stage_event intervals
-- (script, not pure SQL: walk events per stage, sum VISIBLE→HIDDEN gaps up to the last SAVE)

-- M5 with full denominator
SELECT stage, decision_method, status, COUNT(*)
FROM mapping_suggestions
WHERE lower(team_code) = lower(:'team') AND created_at BETWEEN :'from' AND :'to'
GROUP BY stage, decision_method, status;

-- M9
SELECT * FROM readiness_snapshot
WHERE lower(team_code) = lower(:'team') AND computed_at BETWEEN :'from' AND :'to' ORDER BY computed_at;

-- M10 grouped by repository
SELECT coalesce(l.owner || '/' || l.repo, '(no match)') AS repository, a.status, COUNT(*) AS components
FROM source_alignment_result a
LEFT JOIN github_repository_links l ON l.id = a.repository_id
WHERE lower(a.team_code) = lower(:'team')
  AND a.run_id = (SELECT run_id FROM source_alignment_result
                   WHERE lower(team_code) = lower(:'team') ORDER BY analyzed_at DESC LIMIT 1)
GROUP BY 1, 2 ORDER BY 1, 2;
```

**Endpoint outline (optional):** `GET /api/synctrace/admin/session-records?teamCode=&from=&to=&format=csv`, restricted to teachers in `SyncTraceAccessGuard`. It would run the queries above and return a ZIP of one CSV per metric (`m1_m2_m3_m6_m8_runs.csv`, `m4_stage_time.csv`, `m5_suggestions.csv`, `m7_coverage.csv`, `m9_readiness.csv`, `m10_alignment.csv`), plus a `summary.csv` with the pass rate against each target. Effort: **M**.

---

## 5. Risks to validity

1. **Lost history.** Findings, recommendations, `continuity_analysis_runs`, repository status and readiness are overwritten by the next run. If a team re-runs analysis after the session, or a teacher re-runs it while reviewing, the session's values are gone. Until a history is kept, export each team immediately after its session.
2. **Failed runs are invisible.** No operation records failures or 409 conflicts, and `mapping_generation_runs` writes only successes (`MappingSuggestionService.java:191`). A "% of runs ≤ 30 s" figure computed from what exists would be survivorship-biased. Failed and timed-out runs must count against the target.
3. **What "run time" includes differs by outcome:**
   - **M1** includes the Drive download, PDF/page rendering (`MAX_PAGES_TO_RENDER = 999`), the AI call and up to 2 retries with 2–30 s backoff. One transient error can push a run past 30 s, so report `attempts` alongside the time.
   - **M2** includes **no AI call**. It parses an existing evaluator report and will look trivially fast. If the objective means "retrieve components from the IEEE Docs Evaluator, including its analysis", M2 must also count `evaluation_history` generation time, which isn't recorded either.
   - **M6** detection is deterministic (milliseconds). The AI latency is in recommendations and alignment. The only stored timestamp (`last_analyzed_at`) reflects detection, so it would understate a run if used as a proxy.
4. **Server time vs. user-perceived time.** Server-side durations exclude network transfer, browser rendering and backend cold starts (`render.yaml` hosting may sleep when idle). If the objective is user-facing, also capture client-side time (request start → response) in the run log, or report both.
5. **Repeat runs look faster:**
   - Component re-extraction reuses rows (`TraceComponentService.java:275-298`).
   - Mapping suggestions skip already staged and rejected pairs (`MappingSuggestionService.java:95-98, 361`), so later runs send fewer prompts.
   - Ingestion re-fetches every file, so it isn't cached.
   - Report first-run and repeat-run times separately.
6. **Ingestion "success" can be partial.** Rate limiting stops early but returns INGESTED (`GitHubIngestionService.java:183-197`), and the file cap is 500 with files over 300 KB skipped (`:31-32`). A fast run may simply have ingested less. Record `PARTIAL` and the file count, and set a `GITHUB_TOKEN` before sessions.
7. **Stage time:**
   - The `created_at` proxy is wall-clock. It includes idle tabs, breaks and multi-day work, and it starts at the first link rather than at stage open.
   - Deleting early links moves the start later.
   - Activity rows carry the selected stage, not the stage that changed.
   - Several team members map concurrently in a shared workspace, so decide whether stage time is the union of their active intervals or the sum.
8. **M5 skew:**
   - Discards and regenerations remove undecided suggestions, which inflates acceptance.
   - Stale auto-rejections are mixed in with real rejections, which deflates it.
   - Accept All can bulk-accept without review, which inflates it.
   - Teacher decisions mix with student ones (`decided_by` is an email with no role stored). Filter to students.
9. **M7 false coverage.** Empty-string explanations count as rows. Findings the AI skips vanish silently. Recommendations are deleted before the AI call, so a failed run leaves 0 % coverage that looks like a real result.
10. **M9 definition drift.** No Provisional rule exists, so any readiness number reported now penalises all findings, including AI alignment findings. That contradicts the stated method.
11. **M10 data integrity.** Same-path files across two repositories of one team collapse into one component (`GitHubIngestionService.java:260-266`), so per-repository grouping would be wrong for those files.
12. **Timezones.** All timestamps are `LocalDateTime` with no zone, taken from the server clock (likely UTC on Render). Durations are unaffected, but matching runs to session schedules in local time needs a known offset.
13. **AI model and provider variance.** The model comes from runtime settings (`OpenAiProvider.java:89-96`) and isn't recorded with any run, so a mid-study settings change would silently alter latency. Freeze settings during validation and record the model.
14. **Non-atomic writes.** `@Transactional` on private methods is ignored (see M7), so a crash mid-run can leave partial findings or recommendations that later queries will treat as a complete run.
