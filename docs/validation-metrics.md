# Validation Metrics

SyncTrace records the measurable outcomes from the Software Project Proposal (M1–M8) automatically, for every team, from the moment it's deployed. A teacher opens **SyncTrace → Metrics** to see each team's results against its targets, and exports everything in one click.

Recording is always on. Nothing has to be started before a validation session.

---

## 1. What each metric measures

| ID | Metric | Target | One measurement is… | Stored in |
|----|--------|--------|---------------------|-----------|
| M1 | SMART Goal extraction run time | ≤ 30 s per run | one *Extract goals* request | `validation_events` (`metric_code = 'M1'`) |
| M2 | Component retrieval run time | ≤ 30 s per run | one *Extract components* from an IEEE Docs Evaluator report | `validation_events` (`M2`) |
| M3 | GitHub repository preprocessing time | ≤ 30 s per repository | one repository ingestion (each repository separately in *Ingest all*) | `validation_events` (`M3`, `repository_id`) |
| M4 | Mapping stage finalization time | ≤ 15 min per stage | one stage session: from opening the stage until *Save Mapping* | `validation_stage_sessions` |
| M5 | AI-suggested links accepted or edited | ≥ 70 % | every AI suggestion ever generated, with its decision | `mapping_suggestions` (view `ai_link_suggestions`) |
| M6 | Continuity analysis run time | ≤ 60 s per run | one *Run analysis*: gap detection + diagnostic recommendations | `validation_events` (`M6`, grouped by `analysis_run_id`) |
| M7 | Gaps with both an explanation and a recommendation | 100 % | one analysis run's coverage snapshot | `validation_analysis_coverage` |
| M8 | Audit report export time | ≤ 10 s per export | one audit report export (PDF / JSON / CSV) | `validation_events` (`M8`, `export_format`) |

### How results are judged

- **Timed metrics (M1, M2, M3, M6, M8)** report count, median, max and min over *every* recorded run.
  - A run is **within target** only if it succeeded *and* finished within the target. Failed and timed-out runs count as misses.
  - **Met** means % within target ≥ `required-pct-within-target`, which defaults to 100 %, because "≤ 30 s per run" means every run.
  - With no runs, the result is "No data", which is neither met nor not met.
- **M4** uses **active time**; wall-clock time is shown next to it. It's reported for all stages together and per stage. Sessions that were opened but never finalized are counted as "not finalized" and not timed.
- **M5 rate** = (accepted + edited) ÷ (accepted + edited + rejected by a user). Pending suggestions (still undecided, discarded, or replaced by a newer generation) and system rejections (the target component was deleted) are shown but are not part of the rate. Met = rate ≥ 70 %.
- **M7 coverage** = gaps with both ÷ (gaps detected − provisional gaps), added up over the analysis runs that detected at least one gap. Blank text doesn't count as an explanation or a recommendation. Met = coverage ≥ 100 %.

### Changing targets

Every target and timing constant is in **one place**, `backend/src/main/resources/application.properties` (`synctrace.validation.*`), bound to `ValidationProperties`. Change a value there, or override it with an environment variable such as `SYNCTRACE_VALIDATION_TARGETS_M1MAXMS=45000`. No other code holds these numbers. The page reads them from the server.

---

## 2. Where each timer starts and stops

All timing happens on the **server**. Durations come from a monotonic clock (`System.nanoTime()`). `started_at` and `ended_at` are UTC timestamps used for filtering. Every timer goes through one helper, `ValidationTiming.time(...)`, and the timing wraps the whole operation, so AI calls, GitHub calls and retries are included.

| Metric | Code (wrapped call) | Timer starts | Timer stops |
|---|---|---|---|
| M1 | `ProposalAnalysisController.extractSmartGoals` (report's SMART Goals section via `SmartGoalsSection`, else `ProposalAnalysisService.extractSmartGoals`) | after the request is validated, before the evaluation report is loaded | the draft goals are ready to show for review. When the proposal's evaluation report has a SMART Goals section, the goals are read from it on the server. Otherwise this includes the Drive download, page rendering, the AI call and up to 3 attempts. Reviewing and saving the drafts afterwards is not part of M1. |
| M2 | `TraceComponentController.extractTraceComponents` | before loading the evaluator report (`evaluation_history`) | the extracted components are saved. This step makes **no AI call**: it reads the report's Diagram Analysis. |
| M3 | `GitHubRepositoryLinkService.ingest` (linked repositories, including *Ingest all*), `GitHubController.ingestUnlinkedRepository` (by URL) | before the repository is marked INGESTING | after the final INGESTED or FAILED status is saved. Includes the branch lookup, tree listing, every file download and GitHub rate-limit retries. |
| M6 | `ContinuityController.detectContinuityGaps` (step 1, including the wait for the team's analysis lock) and `ContinuityController.generateRecommendations` (step 2, AI) | step 1's request start | step 2's saved recommendations. A run's duration is **last step end − first step start**, and a run failed if either step failed. The Results page sends one `analysisRunId` for both steps. |
| M7 | right after step 2 of M6 finishes or fails | — | coverage is counted from the team's findings and their recommendations |
| M8 | `AuditExportController.exportAuditReport` → `AuditExportService.exportAuditReport` | before the report is built | the file bytes are rendered (`item_count` = file size in bytes) |
| M4 | heartbeat `POST /api/synctrace/validation/stage-heartbeat`; link changes in `StagedMappingController` and `MappingSuggestionController`; *Save Mapping* in `StagedMappingController.sync` | the first heartbeat for a team's stage, or the first link change after a stage was finalized | *Save Mapping* with that stage selected |

### Status of a run

- **success**: the operation completed.
- **timeout**: the error was a timeout anywhere in its cause chain (socket or HTTP timeout, or an exhausted retry time limit).
- **failed**: any other error. The message is kept in `error_message`.

Requests rejected **before any work** are not runs and are not recorded: access denied, a duplicate "already in progress", or an unsupported export format.

### Writes never block or break anything

The timer only *hands* the finished record to `ValidationRecorder`, which writes it on a single background thread. A failure to record is logged (`Validation metric … could not be recorded`) and dropped. The user's action and its response are never affected. `validation_team_summary` is refreshed from the same background thread after each write.

### M4 active time

1. While a mapping stage is open and the browser tab is visible, the mapping page sends a heartbeat every 30 s. It sends a final "hidden" beat when the tab is hidden, the stage changes, or the page closes. This is `useStageHeartbeat`; it's invisible and fire-and-forget.
2. The time between two consecutive beats counts as **active** when the gap is at most **2 minutes**. A longer gap is idle and adds nothing. A "hidden" beat closes the interval. A link change counts like a heartbeat.
3. Heartbeats from several teammates on the same stage form one team timeline, and nothing is counted twice.
4. *Save Mapping* finalizes the open session and stores `wall_clock_ms`, `active_ms`, `finalized_by`, `links_saved` (links in that stage) and `unmapped_sources_at_finalize` (stage sources without a link).
5. **Reopening:** a link added, removed, accepted or edited in a finalized stage starts a **new** session row. Finalized rows are never overwritten. Merely looking at a finalized stage doesn't reopen it.

### M5 suggestion record

Every AI suggestion is kept in `mapping_suggestions`, and nothing is deleted any more:

- *Accept* and *Edit* store `resulting_link_id` (the first staged link created) and `resulting_link_count`. The links keep their origin `AI_ACCEPTED` / `AI_EDITED`.
- *Reject* keeps the row as `REJECTED`.
- *Discard*, and generating a new set of suggestions, leave the earlier undecided rows as `PENDING` with `withdrawn_at` and `withdrawn_reason = discarded | superseded`. They're hidden from the review list exactly as before.
- A suggestion whose target component was deleted is `REJECTED` with `system_rejected = true`.
- Each decision records `decided_by_user_id` and `decision_method = single | accept_all`.

---

## 3. How to export after a validation session

1. **Before or after the session (optional):** in **SyncTrace → Metrics**, choose *Period → Validation session → New session*. Enter a name such as "Validation session — Nov 25, 9–11 AM", the date, the start and end time, and the teams (taken from the team filter). Sessions are only saved filters; recording happens regardless.
2. After the session, open **Metrics** and pick:
   - **Period:** the validation session, a date range, or *All time*.
   - **Teams:** *All teams*, or click one or more team codes.
3. Click **Export** and choose **CSV (zip)** or **Excel (.xlsx)** from the menu that opens. The file covers exactly the teams and period shown.

| Export | Contents |
|---|---|
| **CSV (zip)** | `summary.csv`: one row per team, with `count`, `median_s`, `max_s`, `pct_within_target` and `met` for M1, M2, M3, M4, M6 and M8, plus the M5 counts and acceptance rate and the M7 coverage %. Also four raw files: `validation_events.csv`, `stage_sessions.csv`, `ai_suggestions.csv`, `analysis_coverage.csv`. |
| **Excel** | One workbook with a **Summary** sheet plus one sheet per metric (M1 … M8) holding that metric's raw rows. |

Exports contain team codes and user IDs (Supabase user IDs) only, never names or emails. Text that a spreadsheet could execute as a formula is prefixed with `'`.

**Direct API (teacher login required):** `GET /api/synctrace/validation/export?format=zip|xlsx&teamCodes=A,B&sessionId=1` or `&from=2026-11-25T01:00:00Z&to=2026-11-25T03:00:00Z`. Students get **403** from every `/api/synctrace/validation/*` endpoint except the heartbeat.

**Straight from the database:** the same data is in the tables named in §1. `validation_team_summary` holds each team's all-time summary, refreshed after every event.

---

## 4. Database and migrations

- Schema changes are in **Flyway** migration `backend/src/main/resources/db/migration/V1__validation_metrics.sql`. It creates `validation_events`, `validation_stage_sessions`, `validation_analysis_coverage`, `validation_sessions` and `validation_team_summary`, adds nullable columns to `mapping_suggestions`, and creates the view `ai_link_suggestions`.
- Existing tables are still managed by Hibernate (`ddl-auto=update`), unchanged.
- **First deployment:** on a database without Flyway history, Flyway records a baseline (version 0) and then applies V1. This was verified against a database built by the previous release, and an existing suggestion row survived. The migration is written to also work on an empty database.
- `spring.flyway.enabled` can be turned off with `SPRING_FLYWAY_ENABLED=false`.
- ⚠️ `./mvnw test` starts the full application (`IeeeDocsEvaluatorApplicationTests`), and Flyway migrates **whatever database `SPRING_DATASOURCE_URL` points to**. Point it at a test database when running tests.

## 5. Tests

| Test | Covers |
|---|---|
| `ValidationTimingTest` | durations, item counts, failure / timeout / not-a-run paths, the original exception passing through, a broken recorder not affecting the action |
| `ActiveTimeTest` | active time with idle gaps (exactly 2 min counts, 2 min 1 s doesn't), hidden tabs, interleaved teammates |
| `StageSessionServiceTest` | open, finalize (wall-clock, active time, links, unmapped sources), reopen as a new row, heartbeats not reopening |
| `MappingSuggestionMetricsTest` | rejected rows kept, accept/edit resulting links, discard/regenerate withdrawing instead of deleting, stale = system rejection, accept-all |
| `CoverageCalculatorTest` | blank text, provisional exclusion, failed recommendation step |
| `ValidationSummaryServiceTest` | median, max, min, % within target, met for every metric, checked against hand-computed values |
| `ValidationMetricsAccessTest` | a student gets 403 on the page data, exports and sessions; heartbeats are forced onto the student's own team |
| `ValidationExportServiceTest` | ZIP files and headers, Excel sheets, no emails, CSV escaping |
| `MetricHookTest` | a hooked endpoint records one event and responds exactly as before |
| `ValidationMetricsEndToEndTest` (opt-in) | a full session for a fresh test team through the real controllers, database, recorder and exports. The AI and Google Drive are faked; GitHub is real. |

Run the end-to-end test with:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/synctrace_test \
SPRING_DATASOURCE_USERNAME=... SPRING_DATASOURCE_PASSWORD=... \
./mvnw test -Dtest=ValidationMetricsEndToEndTest -De2e=true -De2e.out=/tmp/e2e
```

It links `octocat/Spoon-Knife`, so it needs network access and a database where that repository isn't linked yet.

---

## 6. Known limits

**Timing scope**
- **Server time, not user-perceived time.** Durations exclude network transfer, browser rendering and a backend cold start (if the host sleeps when idle).
- **M2 contains no AI time.** It times reading an already-evaluated report. The IEEE Docs Evaluator's own analysis is a separate, earlier step.
- **M1 stops when the drafts are ready**, not when the reviewed goals are saved.
- **M6 includes the short gap between its two requests.** It counts only runs started from *Run analysis* on the Results page. Detection calls made without a run id, and source-code alignment, are recorded but not counted toward M6.

**Runs and results**
- **M3 partial ingestions count as success.** If GitHub's rate limit stops an ingestion early, the run is still `success` with fewer files (`item_count`). Add a `GITHUB_TOKEN` in System Settings before validation sessions.
- **Rejected requests are not runs.** A 403, a 400 for a bad format, or a 409 duplicate run leaves no record.
- **M7 is a snapshot taken right after the recommendation step**, on the background thread. A second analysis started within milliseconds could change what it sees.

**M4 stage sessions**
- **Any *Save Mapping* finalizes the stage.** A team that saves halfway and keeps working gets two sessions for that stage.
- **Time before the first change after a reopen isn't counted.** A reopened session starts at the first link change, not when the team returned to the stage.
- **A lost "hidden" beat** (browser crash, lost network) can count up to 2 minutes of extra active time.
- Teacher activity on a team's mapping page counts toward that team's M4 time. `finalized_by` shows who saved.

**Definitions not yet in the code**
- **"Provisional" gaps are not defined in SyncTrace yet**, so `gaps_provisional` is always 0. The rule is one predicate, `CoverageCalculator.NO_PROVISIONAL`. Defining it changes only M7's denominator, not readiness.
- **History starts with this release.** Suggestions discarded before the upgrade were deleted by the old code and can't be recovered.

**Recording and storage**
- **Recording is best-effort by design.** If the background queue (1,000 writes) is full, or the server stops before queued writes run, those records are lost; this is logged. It assumes one backend instance, which is how SyncTrace is deployed.
- **Timestamps.** The new tables store UTC (`timestamptz`). `mapping_suggestions` keeps its existing server-local `timestamp` columns, which the exports convert to UTC.
