# Validation Metrics: Phase 1 Audit and Plan

**Status:** proposal, awaiting approval. No code has been changed.
**Builds on:** [measurability-audit.md](measurability-audit.md), which has the full evidence with file:line references.
**Baseline:** `./mvnw test` on `main` @ `bd4741e` → 179 tests, 0 failures, 0 errors, 1 skipped.

---

## 1. Stack map (what the feature must fit into)

| Area | Finding | Consequence for this feature |
|---|---|---|
| Backend | Spring Boot **4.0.3**, Java, Lombok, Spring Security (custom `JwtAuthenticationFilter` + `SupabaseJwtValidator`) | New code goes under `backend/.../synctrace/validation/` (model, repository, service, controller), following the existing `synctrace/*` layout. |
| DB / ORM | PostgreSQL 17 on Supabase, JPA/Hibernate, `ddl-auto=update` (`application.properties:14`) | **There is no migration tool.** See §7 decision D1. |
| Libraries already present | Apache POI `poi-ooxml` 5.5.1 (`pom.xml:78`), OpenPDF 2.0.3 (`pom.xml:121`) | Excel export needs no new dependency. ZIP comes from `java.util.zip`. |
| Flyway | The Boot 4.0.3 BOM manages Flyway 11.14.1 and `spring-boot-starter-flyway` | Adding it needs `spring-boot-starter-flyway` + `flyway-database-postgresql`, with no version pins. |
| Teams | No teams table. Teams come from the Google Sheets roster (`SubmissionSyncService.getTeamRepositories()` → `TeamRepository(teamCode, githubUrl)`). Every SyncTrace table keys on the `team_code` string. | `team_id` = `team_code` (decision D2). |
| Users | The principal is `AuthenticatedUser(email, role, groupCode)` (`security/AuthenticatedUser.java`). The validator returns only the `email` claim (`SupabaseJwtValidator.java:102-103`), although the Supabase JWT also carries `sub` (the user UUID). | Exports must not contain emails, so a user ID is needed (decision D3). |
| Auth / scoping | `SecurityConfig` requires authentication for `/api/synctrace/**`. Role checks live in `SyncTraceAccessGuard` (`isStudent()`, `resolveEffectiveTeamCode()`, which forces students onto their own team). There are no `hasRole` matchers. | Add `SyncTraceAccessGuard.requireTeacher()` and call it in every metrics endpoint (§4). |
| Jobs | No background jobs, `@Async` or schedulers. Every operation runs synchronously in its HTTP request. | "Whole job" = the request. Metric writes go to a small dedicated executor so they never add latency. |
| AI wrapper | `AiProvider.complete()` → `OpenAiProvider`. Each service has its own retry loop (3 attempts, 180 s budget). Read timeout is 300 s. | The timer wraps the service call, so retries and AI time are included automatically. |
| Progress | `ProgressEmitter` SSE, in memory | Not used for metrics. |
| Export code | `AuditExportController` / `AuditExportService` (JSON/CSV/PDF) | Hooked for M8. The new metrics export is a separate service. |
| Frontend | React 19 + Vite, **no router** (History API, `synctrace/routes.js`), `lucide-react` icons, no test runner | New page plugs into `VIEW_TO_SEGMENT` / `SEGMENT_TO_VIEW` and the `SyncTraceDashboardPage` view switch. |
| Teacher SyncTrace sidebar | `synctrace/components/teacher/SyncTraceSidebar.jsx` `NAV_ITEMS` = Overview / Source Code / Mapping / Results | Add a "Validation Metrics" item here. The SyncTrace workspace is teacher-only; students use `StudentDashboardPage`. |
| Mapping UI | Teacher `TraceabilityMappingPage` and student `StudentTraceabilityMapping` both use `useStagedTraceability.js` | The heartbeat goes into that hook, so it covers both. |
| Tests | JUnit 5 + Mockito unit tests that call controllers/services directly. `IeeeDocsEvaluatorApplicationTests` boots the full context **against the live Supabase DB**. | New tests follow the Mockito style. See decision D1 on the live DB. |

---

## 2. Per-metric audit: recorded, partial, missing

| ID | Already recorded | Partial | Missing | Plan |
|---|---|---|---|---|
| **M1** Goal extraction | Nothing. `POST /proposals/extract-goals` returns drafts without saving (`ProposalAnalysisController.java:28-46`). | — | Start, end, duration, status, team, user | **Add** an M1 event around the controller call |
| **M2** Component retrieval | Nothing. `POST /components/extract` (`TraceComponentController.java:207-249`). Per-component `created_at` only, and re-runs reuse rows (`TraceComponentService.java:275-298`). | — | Everything. Note: this path makes **no AI call** (it parses an existing evaluator report). | **Add** an M2 event. Team is derived from `evaluation_history.file_name`. |
| **M3** Repo preprocessing | `github_repository_links.ingestion_status`, `ingested_file_count`, `last_ingested_at`, `last_ingest_error` (`GitHubRepositoryLinkService.java:177-197`). Last run only. | Per-file `trace_components.source_captured_at` window | Start time, duration, history, failed-run duration | **Add** an M3 event per repository in `GitHubRepositoryLinkService.ingest` (covers single and ingest-all) and in the unlinked path |
| **M4** Stage finalization | `staged_trace_mappings.created_at` (`StagedMappingService.java:109`). `traceability_mapping_activity.performed_at` + `stage` on Save (`StagedMappingController.java:92-105`). | Wall-clock proxy only | Stage open, finalize, active time, reopen history | **Add** `validation_stage_sessions` + heartbeat + finalize hook on Save Mapping |
| **M5** AI links accepted/edited | `mapping_suggestions` (team, stage, source, target, reason, `created_at`, status PENDING/ACCEPTED/EDITED/REJECTED, `decided_at`, `decided_by`) (`MappingSuggestion.java:9-26`). REJECTED rows **are kept** (`MappingSuggestionService.java:226-231`). Link origin MANUAL/AI_ACCEPTED/AI_EDITED (`StagedTraceMapping.java:43-51`). | Most fields | `resulting_link_id`. PENDING rows are **hard-deleted** on Discard and on regenerate (`MappingSuggestionService.java:182-183, 283-288`). Stale-target auto-rejections are indistinguishable from user rejections (`:390-396`). | **Reuse** `mapping_suggestions` (add columns, stop deleting) and expose it as view `ai_link_suggestions` |
| **M6** Continuity analysis | `continuity_analysis_runs.last_analyzed_at` (one overwritten row per team) | A timestamp only | Duration, run history. The run spans **two requests**: `/detect-gaps` then `/recommendations` (`useAiContinuityAnalysis.js:63-67`). | **Add** an M6 event per step, grouped by a new `analysis_run_id` sent by the client |
| **M7** Gap explanation coverage | `diagnostic_recommendations.root_cause` + `recommendation` per `finding_id` | Computable for the current state only | Per-run record. Blank texts count as rows. AI-omitted findings vanish silently. **No "Provisional" concept exists** (0 matches in code). | **Add** `validation_analysis_coverage`, written after each recommendations step |
| **M8** Audit export | Nothing (`AuditExportController.java:28-64`) | — | Everything | **Add** an M8 event with `export_format` |

---

## 3. Schema

All new objects are created by one Flyway migration, `V1__validation_metrics.sql` (decision D1). Existing tables get only **additive, nullable** columns.

### 3.1 `validation_events` (M1, M2, M3, M6, M8)

```sql
CREATE TABLE validation_events (
  id               BIGSERIAL PRIMARY KEY,
  team_id          VARCHAR(120),               -- team_code (D2); null only if unresolvable
  user_id          VARCHAR(64),                -- Supabase sub (D3); never an email
  metric_code      VARCHAR(4)  NOT NULL CHECK (metric_code IN ('M1','M2','M3','M6','M8')),
  operation        VARCHAR(40) NOT NULL,       -- GOAL_EXTRACTION, COMPONENT_RETRIEVAL, REPO_INGESTION,
                                               -- GAP_DETECTION, RECOMMENDATIONS, ALIGNMENT, AUDIT_EXPORT
  repository_id    BIGINT,                     -- github_repository_links.id (M3)
  export_format    VARCHAR(8),                 -- pdf | json | csv (M8)
  analysis_run_id  VARCHAR(64),                -- groups M6 steps of one "Run analysis"
  started_at       TIMESTAMPTZ NOT NULL,
  ended_at         TIMESTAMPTZ NOT NULL,
  duration_ms      BIGINT      NOT NULL,       -- from System.nanoTime(), not ended-started
  status           VARCHAR(10) NOT NULL CHECK (status IN ('success','failed','timeout')),
  error_message    TEXT,
  item_count       INTEGER,                    -- goals / components / files / findings / bytes
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_validation_events_team_metric_time ON validation_events (team_id, metric_code, started_at);
CREATE INDEX ix_validation_events_run ON validation_events (analysis_run_id);
```

`operation` is one column beyond the spec. M6 needs it to tell its two steps apart, and it lets alignment runs be stored without counting toward M6 (decision D6).

### 3.2 `validation_stage_sessions` (M4)

```sql
CREATE TABLE validation_stage_sessions (
  id                            BIGSERIAL PRIMARY KEY,
  team_id                       VARCHAR(120) NOT NULL,
  stage                         VARCHAR(20)  NOT NULL CHECK (stage IN
                                  ('proposal_srs','srs_sdd','srs_std','srs_spmp','sdd_impl')),
  started_at                    TIMESTAMPTZ NOT NULL,
  finalized_at                  TIMESTAMPTZ,
  wall_clock_ms                 BIGINT,                  -- finalized_at - started_at
  active_ms                     BIGINT NOT NULL DEFAULT 0,
  last_heartbeat_at             TIMESTAMPTZ,             -- internal: open interval marker
  finalized_by                  VARCHAR(64),             -- user_id
  links_saved                   INTEGER,
  unmapped_sources_at_finalize  INTEGER,
  created_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- at most one open session per team+stage; reopening inserts a new row
CREATE UNIQUE INDEX ux_stage_session_open ON validation_stage_sessions (team_id, stage)
  WHERE finalized_at IS NULL;
```

Stage values map one-to-one to the existing keys: `PROPOSAL_SRS→proposal_srs`, `SRS_SDD→srs_sdd`, `SRS_STD→srs_std`, `SRS_SPMP→srs_spmp`, `SDD_IMPLEMENTATION→sdd_impl` (`StagedMappingService.java:28-34`).

### 3.3 M5: reuse `mapping_suggestions` and add the view `ai_link_suggestions`

`mapping_suggestions` already has the columns the spec asks for, so no new table is created. Additive columns:

```sql
ALTER TABLE mapping_suggestions
  ADD COLUMN IF NOT EXISTS resulting_link_id   BIGINT,       -- staged_trace_mappings.id (first if EDIT → many)
  ADD COLUMN IF NOT EXISTS resulting_link_count INTEGER,
  ADD COLUMN IF NOT EXISTS decided_by_user_id  VARCHAR(64),
  ADD COLUMN IF NOT EXISTS decision_method     VARCHAR(12),  -- single | accept_all
  ADD COLUMN IF NOT EXISTS withdrawn_at        TIMESTAMPTZ,  -- left pending: discarded or superseded
  ADD COLUMN IF NOT EXISTS withdrawn_reason    VARCHAR(12),  -- discarded | superseded
  ADD COLUMN IF NOT EXISTS system_rejected     BOOLEAN NOT NULL DEFAULT FALSE; -- target deleted (stale)

CREATE VIEW ai_link_suggestions AS
SELECT id, team_code AS team_id, lower(stage) AS stage, source_id,
       target_component_id AS target_id, reason, score, run_id,
       created_at AS generated_at,
       lower(status) AS decision,          -- pending | accepted | edited | rejected
       decided_at, decided_by_user_id AS decided_by, decision_method,
       resulting_link_id, resulting_link_count,
       withdrawn_at, withdrawn_reason, system_rejected
FROM mapping_suggestions;
```

Behavior change (this is the "persist AI suggestions" item the spec allows):
- `MappingSuggestionRepository.deletePendingByTeamAndStage` is no longer called. A new `markPendingWithdrawn(team, stage, reason, at)` `UPDATE` sets `withdrawn_at` / `withdrawn_reason` instead.
- `pending()` (`MappingSuggestionService.java:197-205`) gains `withdrawn_at IS NULL`, so the UI shows exactly what it shows today.
- `decide()` stores `resulting_link_id` and `resulting_link_count` from the returned staged rows, plus `decided_by_user_id` and `decision_method`.
- `markStale()` keeps status REJECTED (unchanged behavior) and sets `system_rejected = true`.
- Link origins stay as they are: ACCEPT → `AI_ACCEPTED`, EDIT → `AI_EDITED` (`:249-256`).

### 3.4 `validation_analysis_coverage` (M7)

```sql
CREATE TABLE validation_analysis_coverage (
  id                        BIGSERIAL PRIMARY KEY,
  analysis_run_id           VARCHAR(64) NOT NULL,
  team_id                   VARCHAR(120) NOT NULL,
  gaps_detected             INTEGER NOT NULL,
  gaps_provisional          INTEGER NOT NULL DEFAULT 0,
  gaps_with_explanation     INTEGER NOT NULL,   -- non-blank root_cause
  gaps_with_recommendation  INTEGER NOT NULL,   -- non-blank recommendation
  gaps_with_both            INTEGER NOT NULL,
  generation_failures       INTEGER NOT NULL,   -- non-provisional gaps left without both
  recommendation_status     VARCHAR(10) NOT NULL, -- success | failed | timeout
  created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_coverage_team_time ON validation_analysis_coverage (team_id, created_at);
```

Coverage % = `gaps_with_both / (gaps_detected − gaps_provisional)`. Zero gaps gives 100 % ("nothing left unexplained"); this is flagged in D7.

### 3.5 `validation_sessions` (optional filter)

```sql
CREATE TABLE validation_sessions (
  id                 BIGSERIAL PRIMARY KEY,
  name               VARCHAR(200) NOT NULL,      -- "Validation session — Nov 25, 9–11 AM"
  session_date       DATE NOT NULL,
  starts_at          TIMESTAMPTZ NOT NULL,
  ends_at            TIMESTAMPTZ NOT NULL CHECK (ends_at > starts_at),
  team_ids           TEXT NOT NULL DEFAULT '',   -- comma-separated team codes; empty = all teams
  created_by         VARCHAR(64),
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Recording is always on. A session is only a saved filter: it matches rows whose start time falls in `[starts_at, ends_at]`. For stage sessions the start time is `started_at`, and for coverage rows it is `created_at`.

### 3.6 `validation_team_summary` (table, refreshed after each event)

I chose a **table** over a SQL view so the summary math exists in exactly one place, the Java `ValidationSummaryService`, and the targets in exactly one place, the config. A view would have to repeat both in SQL. After each recorded event, the recorder schedules a debounced refresh for that team (at most one per team every 5 s, on the metrics executor) and writes all-time rows here. The page and the exports use the same service with the chosen filters.

```sql
CREATE TABLE validation_team_summary (
  team_id            VARCHAR(120) NOT NULL,
  metric_code        VARCHAR(4)   NOT NULL,
  stage              VARCHAR(20)  NOT NULL DEFAULT '',   -- '' = all stages; M4 also gets one row per stage
  count              INTEGER NOT NULL,
  median_ms          BIGINT, max_ms BIGINT, min_ms BIGINT,
  pct_within_target  NUMERIC(5,1),
  target_met         BOOLEAN,
  accepted INTEGER, edited INTEGER, rejected INTEGER, pending INTEGER,
  acceptance_rate    NUMERIC(5,1),
  coverage_pct       NUMERIC(5,1),
  last_updated       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (team_id, metric_code, stage)
);
```

### 3.7 Targets: one config object

`ValidationTargets` is a `@ConfigurationProperties("synctrace.validation")` record, bound from `application.properties` and overridable by environment variables. Nothing else in the code holds a number.

```properties
synctrace.validation.targets.m1-max-ms=30000
synctrace.validation.targets.m2-max-ms=30000
synctrace.validation.targets.m3-max-ms=30000
synctrace.validation.targets.m4-max-ms=900000
synctrace.validation.targets.m5-min-rate=70
synctrace.validation.targets.m6-max-ms=60000
synctrace.validation.targets.m7-min-coverage=100
synctrace.validation.targets.m8-max-ms=10000
synctrace.validation.targets.required-pct-within-target=100   # see D5
synctrace.validation.heartbeat-interval-ms=30000
synctrace.validation.idle-gap-ms=120000
```

The frontend never hard-codes targets. It reads them from `GET /api/synctrace/validation/targets`.

---

## 4. Recording design

### 4.1 One helper

```java
// synctrace/validation/service/ValidationTiming.java
public <T> T time(MetricCode metric, ValidationContext ctx, ThrowingSupplier<T> body) throws Exception
```

- Takes `Instant.now()` for `started_at` and `System.nanoTime()` for the duration.
- Runs `body`.
- In `finally`, builds the event: `success`, `timeout` (the cause chain has `SocketTimeoutException`, `TimeoutException`, or the existing "Retry time limit exceeded" / suggestion-deadline errors), or `failed`.
- Rethrows the **original** exception unchanged, so controllers keep their current error responses.
- `ctx` carries team, user, repository, format, analysis run ID, and an optional `itemCounter` (result → `Integer`).
- Hands the event to `ValidationRecorder.record(event)`, which **only enqueues** onto a bounded single-thread executor (queue 1 000; when full, it drops and logs). The write runs on that thread in its own transaction. Any exception is caught and logged with `log.warn("validation metric write failed …")`. It can never reach the user's request and adds no blocking DB call to it.
- Requests rejected before work starts (403, 400, and 409 "already in progress") are not runs and are not recorded. This is documented as a known limit.

### 4.2 Where each timer starts and stops

| Metric | Hook (wraps) | Starts | Stops |
|---|---|---|---|
| M1 | `ProposalAnalysisController.extractSmartGoals` → `proposalAnalysisService.extractSmartGoals(...)` (`:43-44`) | After input validation, before the Drive download | Drafts parsed and ready to return. Includes Drive, rendering, AI and retries. |
| M2 | `TraceComponentController.extractTraceComponents` body (`:212-243`) | Before loading `evaluation_history` | After `persistExtractedComponents` returns |
| M3 | `GitHubRepositoryLinkService.ingest(...)` (`:175-203`); `GitHubController.ingestUnlinkedRepository` (`:234`) | Before status → INGESTING | After the INGESTED or FAILED status is saved. Includes every GitHub API call and rate-limit retries. Ingest-all records one event per repo. |
| M6 | `ContinuityController.detectContinuityGaps` (`:98-99`, **including** the wait on `TeamAnalysisLock`) and `generateRecommendations` (`:121-122`) | Each step's request start | Each step's saved result. A run's duration is `max(ended_at) − min(started_at)` over the events sharing `analysis_run_id`. A run fails if either step failed. |
| M7 | After the recommendations step finishes or fails (same controller) | — | Coverage computed on the metrics thread from `continuity_findings` + `diagnostic_recommendations` for the team |
| M8 | `AuditExportController.exportAuditReport` → `auditExportService.exportAuditReport` (`:36`) | Before `buildReport` | Bytes rendered. `item_count` = byte size. |

**Client change for M6 (invisible).** `useAiContinuityAnalysis.js` generates one `analysisRunId` per click and adds it to both request bodies. If it's missing (an old client), the server generates its own per request.

### 4.3 M4 stage sessions and active time

**Heartbeat.** A new hook, `useStageHeartbeat(teamCode, stageKey)`, is called inside `useStagedTraceability`. While a stage is selected and `document.visibilityState === 'visible'`, it POSTs `/api/synctrace/validation/stage-heartbeat {teamCode, stage, state:'visible'}` every `heartbeat-interval-ms`. It sends `state:'hidden'` on tab hide, stage change and `pagehide` (via `fetch(..., {keepalive:true})`). It is fire-and-forget, errors are swallowed, and there is no UI. The endpoint is team-scoped by `resolveEffectiveTeamCode` (students only for their own team), enqueues, and returns 204.

**Server rules (`StageSessionService`):**
1. **Open.** A `visible` heartbeat with no session ever recorded for (team, stage) inserts one with `started_at = now`.
2. **Accumulate** (pure function `ActiveTime.accumulate(last, now, idleGap)`). If `last != null` and `0 < now − last ≤ idle-gap-ms`, add `now − last` to `active_ms`. Then set `last = now`, or `null` on `hidden`, so a hide always closes the interval. Gaps over 2 minutes add nothing. Applied as one atomic `UPDATE`, so heartbeats from several teammates interleave into one team-level timeline and no time is counted twice.
3. **Finalize.** `StagedMappingController.sync` runs with `stage` (Save Mapping) and the stage's open session meets the finalize rule (D4). The service sets `finalized_at`, `wall_clock_ms`, the final active delta, `finalized_by`, `links_saved` (staged links in that stage) and `unmapped_sources_at_finalize`. Sources are computed with the same rule `MappingSuggestionService.sources()` uses; read-only, no link computation changes.
4. **Reopen.** A mapping change in a stage whose latest session is finalized inserts a **new** row. The changes covered are a staged add or remove (`StagedMappingService.add/remove`) and an AI accept or edit. Heartbeats alone never reopen a finalized stage; otherwise just looking at a finished stage would start a new session.
5. Sessions never finalized show as "open" and are excluded from duration stats but counted (see D5).

### 4.4 M7 coverage counting

For the team's findings at the end of the recommendations step:
- `gaps_detected` = all findings.
- `gaps_provisional` = per D7.
- `with_explanation` / `with_recommendation` = a recommendation row with **non-blank** `root_cause` / `recommendation`.
- `with_both` = both non-blank.
- `generation_failures` = non-provisional gaps without both. If the step failed, that is all of them.

This changes nothing in how gaps, recommendations or readiness are computed.

---

## 5. API (all new endpoints under `/api/synctrace/validation`)

| Method / path | Who | Purpose |
|---|---|---|
| `GET /targets` | teacher | The targets config, for display |
| `GET /teams` | teacher | Roster teams + teams with recorded data |
| `GET /summary?teamCodes=A,B&sessionId=&from=&to=` | teacher | Per-team, per-metric stats (§3.6 shape) |
| `GET /export?format=zip\|xlsx&teamCodes=&sessionId=&from=&to=` | teacher | One-click export |
| `GET /sessions`, `POST /sessions`, `DELETE /sessions/{id}` | teacher | Validation-session windows |
| `POST /stage-heartbeat` | student (own team) + teacher | M4 heartbeat |

Every teacher endpoint calls the new `SyncTraceAccessGuard.requireTeacher()` first and throws `AccessDeniedException` → **403**, matching how the existing controllers map it (`@ExceptionHandler`). The check is in the server; the UI hiding the page is extra, not the control.

---

## 6. UI: Validation Metrics page (teachers only)

- **Navigation.** Add `{ key: 'metrics', label: 'Validation Metrics' }` to `SyncTraceSidebar.jsx` `NAV_ITEMS` after Results. Add `metrics ↔ 'metrics'` to `routes.js` and a `currentView === 'metrics'` branch in `SyncTraceDashboardPage.jsx`. The URL is `/synctrace/metrics`.
- **Page.** `synctrace/pages/teacher/ValidationMetricsPage.jsx` + `.css`, using the existing layout, panel, button and table classes and `PanelHeader`/`InfoHint` from `components/common`.
  - **Filters bar.** Team multi-select (checkbox list with "All"), a validation-session dropdown, or a from/to date range. A **New session** button opens `AppModal` with name, date, start and end time, and teams.
  - **Per-team card.** A table with one row per metric M1–M8: Target · Count · Median · Max · % within target · Met/Not met badge. M4 rows expand per stage, with active vs wall-clock time. M5 shows accepted/edited/rejected/pending and the rate. M7 shows coverage %. Empty metrics show "No runs recorded".
  - **Export.** Buttons: **Export CSV (zip)** and **Export Excel**. They apply to the selected teams, so selecting one team exports one team.
- Students can't navigate to it (it lives only in the teacher SyncTrace workspace), and the server returns 403 if they call the endpoints.

### Export contents

- **`summary.csv`**: one row per team. For each of M1, M2, M3, M4, M6, M8: `count, median_s, max_s, pct_within_target, met`. For M5: `accepted, edited, rejected, pending, acceptance_rate, met`. For M7: `runs, coverage_pct, met`.
- **Raw files**: `validation_events.csv`, `stage_sessions.csv`, `ai_suggestions.csv` (from the `ai_link_suggestions` view), `analysis_coverage.csv`.
- **Excel**: one workbook (POI `XSSFWorkbook`) with a Summary sheet plus sheets M1 … M8, each holding that metric's raw rows.
- **No names or emails.** Exports carry team codes and `user_id` only. `mapping_suggestions.decided_by` (email) is never exported. A test asserts that no `@` appears in any exported cell.

---

## 7. Decisions needed from you

| # | Decision | My recommendation |
|---|---|---|
| **D1** | **Migrations.** The project has none (`ddl-auto=update`). Also, `mvn test` boots against the **live Supabase DB**, so a migration (or new entity) would apply to production the first time tests or the app run. | Add **Flyway** for the new objects only: `baseline-on-migrate=true`, `baseline-version=0`, one `V1__validation_metrics.sql` written defensively with `IF NOT EXISTS`. Keep `ddl-auto=update` for existing entities, so their behavior is unchanged. Before running the tests in Phase 3, point them at a **separate test database** (a second Supabase project or local Postgres via `SPRING_DATASOURCE_URL`). Tell me which, or confirm it's fine to apply V1 to the current DB. |
| **D2** | `team_id` = the existing `team_code` string (there's no teams table) | Yes |
| **D3** | `user_id` source | Extend `SupabaseJwtValidator` to also return the `sub` claim and add `userId` to `AuthenticatedUser`, keeping a compatible constructor so existing code and tests don't change. Alternative: a salted hash of the email (no auth change, but not a real ID). |
| **D4** | **What "finalize" means.** There's no finalize action; Save Mapping is the closest. | Any Save Mapping on that stage finalizes the open session and records `unmapped_sources_at_finalize`. Further edits reopen it as a new row. The alternative (finalize only when 0 sources are unmapped) means teams that never fully map never finalize. |
| **D5** | **When is a timing target "met"?** And do failed/timeout runs count? | `pct_within_target` counts failed and timeout runs as **not** within target. "Met" = `pct_within_target ≥ required-pct-within-target`, default **100 %** ("≤ 30 s per run" read literally). Set it to 90 or 95 if your proposal allows that. For M4, judge **active time** and show wall-clock beside it. |
| **D6** | Does source-code alignment (the Source Code page AI step) count toward **M6**? | Record it (`operation=ALIGNMENT`) but exclude it from M6's target. M6 = detect + recommendations, the "Run analysis" action on Results. |
| **D7** | **"Provisional" gaps.** The concept doesn't exist, and readiness must not change. | Until you define it, `gaps_provisional` = 0 and M7's denominator is all gaps. Possible definition: SDD→Implementation alignment findings (`goal_id IS NULL`). If you choose that, only M7's denominator uses it; readiness stays untouched. Also: should a run with 0 gaps count as 100 % or be excluded? I recommend excluding it from the M7 average. |
| **D8** | **M5 denominator** | Rate = (accepted + edited) / (accepted + edited + user-rejected). Report pending (including withdrawn) and system-rejected as separate columns, not in the rate. |
| **D9** | **End-to-end run (Phase 3).** It needs the real Drive proposal, an evaluator report, a GitHub repo, OpenAI, and a teacher login through Supabase/Google OAuth. | Give me: the DB to use (see D1), a **test team code** with a proposal file and an evaluated report, a small public repo URL, and whether you'll sign in to the browser for me (I can drive the UI with Playwright once a session is logged in). |
| **D10** | Should teacher activity on the mapping page count toward a team's M4 time? | Yes: M4 is team-level. `finalized_by` keeps who saved. |

---

## 8. Tests (Phase 3)

All are JUnit 5 + Mockito in the existing style (`backend/src/test/java/com/ieee/evaluator/synctrace/...`).

| Test class | Covers |
|---|---|
| `ValidationTimingTest` | Success records duration ≥ the work's sleep and `item_count`. Failure records `failed` and rethrows the **same** exception. A timeout exception (and "Retry time limit exceeded") records `timeout`. A recorder that throws still lets the action return normally. |
| `ActiveTimeTest` | Heartbeats at 0, 30, 60 s → 60 s. A 5-min gap adds 0. A `hidden` break closes the interval. Two interleaved users don't double-count. An exactly 120 s gap counts and 121 s doesn't. |
| `StageSessionServiceTest` | Open on first heartbeat. Finalize sets wall/active/links/unmapped. Reopen after a mapping change creates a **new** row. A heartbeat after finalize doesn't reopen. |
| `MappingSuggestionMetricsTest` | Reject keeps the row and decision. Discard and regenerate set `withdrawn_*`, so the rows remain pending. `pending()` still hides withdrawn rows. Accept and edit set `resulting_link_id` and origin. Stale sets `system_rejected`. |
| `CoverageCalculatorTest` | Blank texts don't count. Provisional rows are excluded from the denominator. The failed step gives all gaps as failures. Zero gaps follows D7. |
| `ValidationSummaryServiceTest` | Fixed datasets with hand-computed median (odd and even counts), max, min, % within target, met; M4 per stage; M5 rate; M7 coverage; session and date filtering. |
| `ValidationMetricsAccessTest` | A student gets **403** on `/summary`, `/export`, `/sessions`, `/targets` and `/teams`; a teacher gets 200. A student's heartbeat is forced onto their own team. |
| `ValidationExportServiceTest` | The ZIP has the 5 files with headers. The XLSX has Summary + M1…M8 sheets. No cell contains `@`. |
| Hook tests | Extend the existing controller tests to assert one event is recorded per call, and that the response is unchanged when the recorder throws. |

Then `./mvnw test` runs the full suite (baseline: 179 tests, 0 failures, 1 skipped), plus `npm run lint` and `npm run build` for the frontend. The frontend has no test runner, so the heartbeat logic is kept thin and the active-time math lives on the server, where it is tested.

**End-to-end** (per D9) on a test team: extract goals → retrieve components → ingest a repo → map and Save one stage, accepting, editing and rejecting at least one AI suggestion → run analysis → export PDF and CSV → open Validation Metrics → export, then show `summary.csv`.

**Docs:** `docs/validation-metrics.md` will cover what each metric measures, the exact start and stop points (§4.2), how to export after a session, and known limits.

---

## 9. Files to be touched (Phase 2)

**New, backend** (`backend/src/main/java/com/ieee/evaluator/synctrace/validation/`):
- `config/ValidationTargets.java`
- `model/ValidationEvent.java`, `model/StageSession.java`, `model/AnalysisCoverage.java`, `model/ValidationSession.java`, `model/TeamSummaryRow.java`
- Repositories for each model
- `service/ValidationTiming.java`, `service/ValidationRecorder.java`, `service/ActiveTime.java`, `service/StageSessionService.java`, `service/CoverageCalculator.java`, `service/ValidationSummaryService.java`, `service/ValidationExportService.java`
- `controller/ValidationMetricsController.java`
- `resources/db/migration/V1__validation_metrics.sql`

**Modified, backend** (hooks only, plus additive changes):
- `pom.xml` (Flyway), `application.properties` (targets, Flyway flags)
- `SyncTraceAccessGuard` (`requireTeacher`, `currentUserId`)
- `AuthenticatedUser`, `SupabaseJwtValidator`, `JwtAuthenticationFilter` (only if D3 = `sub`)
- `ProposalAnalysisController`, `TraceComponentController`, `GitHubRepositoryLinkService`, `GitHubController`, `ContinuityController`, `AuditExportController`, `StagedMappingController`, `StagedMappingService` (reopen signal)
- `MappingSuggestion`, `MappingSuggestionService`, `MappingSuggestionRepository`

**New / modified, frontend:**
- `synctrace/pages/teacher/ValidationMetricsPage.jsx` + `.css`, `synctrace/hooks/useStageHeartbeat.js`
- `synctrace/api.js` (new calls + `analysisRunId`), `routes.js`, `SyncTraceDashboardPage.jsx`, `SyncTraceSidebar.jsx`, `useStagedTraceability.js`, `useAiContinuityAnalysis.js`

**Unchanged by design:** readiness (`ContinuityReadinessService`), gap detection (`ContinuityGapDetectionService`, `TraceabilityClusterService`), link derivation (`StagedMappingService.sync` / `deriveGoalLinks`), and all existing response shapes.
