# SyncTrace

**SyncTrace: An Intelligent Project Continuity and Traceability Assistant**

SyncTrace is a web-based project continuity and traceability assistant for software engineering capstone projects. Teachers and students use it to build and check each team's trace one stage at a time: from the **SMART Goals** in the proposal to the SRS, from the SRS to the SDD, STD and SPMP, and from the SDD to the team's source code on GitHub.

## Features

- **SMART Goal extraction**: AI pulls SMART Goals out of a team's project proposal for the user to review and save
- **Component library**: Components (use cases, diagrams, tasks, test cases, etc.) extracted from the team's documents
- **GitHub integration**: link a repository (and branch) to each team and ingest its source files as implementation components
- **Sequential (staged) traceability mapping**: a shared mapping workspace per team, where each stage links an upstream artifact to its downstream artifacts:
  `Proposal → SRS`, `SRS → SDD`, `SRS → STD`, `SRS → SPMP`, `SDD → Implementation`
- **AI mapping suggestions**: generated suggestions that the user accepts or rejects
- **Continuity verification**: source code alignment and gap detection over the saved stage-by-stage mapping chain
- **AI diagnostics and recommendations**: an explanation and corrective recommendation for each gap found
- **Interactive traceability matrix** and per-goal traceability results
- **Teacher overview** of every team's traceability progress, plus a per-group drill-down
- **Student traceability view**: students map and review their own team's traceability
- **Audit report export** as JSON, CSV, or PDF

## Traceability Model

SyncTrace follows a **sequential, stage-by-stage** traceability model. Each stage links artifacts from one document to the next document downstream, so every link is between neighbouring stages rather than straight from a goal to any component:

```
Stage 1   Proposal (SMART Goals)  ──►  SRS   (use cases, activity diagrams, wireframes, context / DFD …)
Stage 2   SRS                     ──►  SDD   (class, sequence, UI, data model …)
Stage 3   SRS                     ──►  STD   (test designs, test cases, test logs)
Stage 4   SRS                     ──►  SPMP  (tasks, deliverables, milestones)
Stage 5   SDD                     ──►  Implementation  (classes, functions/modules from GitHub)
```

Users pick a stage, choose a source artifact from the upstream document, and link it to target artifacts in the downstream document, either by hand or by accepting AI mapping suggestions. A downstream artifact is reached only through its upstream link: an implementation component is traced via the SDD component it realises, which is traced via its SRS component, which is traced via its SMART Goal.

When the mapping is saved, SyncTrace follows the staged chain to work out what each SMART Goal ultimately reaches, which drives the traceability matrix and results. Continuity analysis then looks for breaks in the chain (missing stages, unlinked artifacts, missing counterparts such as a use case with no sequence diagram or test case), flags the gaps, and produces diagnostics and recommendations.

## Tech Stack

| Layer | Technology |
|-------|------------|
| Frontend | React 19 + Vite 7 (History API-based routes, no router library) |
| Backend | Spring Boot 4.0 (Java 21), Spring Security, Spring Data JPA |
| Database | Supabase PostgreSQL (schema managed by Hibernate `ddl-auto=update`) |
| Auth | Google OAuth via Supabase Auth; backend checks Supabase JWTs (JWKS) |
| Access control | Google Sheets allowlist (`Teachers` / `Students` tabs) |
| Documents | Google Drive API (OAuth refresh token), Apache Tika, Apache POI |
| AI provider | OpenAI API (model is chosen in Settings, default `gpt-4o-mini`) |
| Source code | GitHub REST API |
| Reports | OpenPDF (PDF audit reports) |
| Hosting | Render (backend, Docker) + Vercel (frontend) |

## Project Structure

```
SyncTrace/
├── backend/                              Spring Boot API (Maven, Dockerfile)
│   └── src/main/java/com/ieee/evaluator/
│       ├── config/                       Google, CORS, security, startup config checks
│       ├── security/                     Supabase JWT filter and validator
│       └── synctrace/                    SyncTrace module (/api/synctrace/**)
│           ├── controller/               goals, components, github, staged-mappings,
│           │                             mapping-suggestions, continuity, proposals, audit
│           ├── model/  repository/
│           └── service/                  ingestion, mapping, gap detection, recommendations, export
│
├── frontend/                             React + Vite app
│   └── src/synctrace/
│       ├── pages/teacher/                overview, mapping, results, source code, group pages
│       ├── components/                   matrix, mapping workspace, results, dialogs
│       ├── hooks/  utils/
│       └── routes.js                     /synctrace/* routing
│
├── docs/                                 SRS, SDD, proposal, and implementation phase notes
├── render.yaml                           Render blueprint for the backend
└── README.md
```

### Frontend routes

| Path | View |
|------|------|
| `/synctrace/overview` | Teacher overview of every team |
| `/synctrace/tmapping` | Traceability mapping workspace |
| `/synctrace/results` | Traceability results and issue analysis |
| `/synctrace/source` | Linked GitHub repositories and ingestion |
| `/synctrace/group/:teamCode` | Traceability details for one group |

Students open traceability mapping and results from the **Traceability** tab of their dashboard.

## Prerequisites

- **Node.js** 20.19+ (required by Vite 7) and npm
- **Java 21** (Maven is optional; use the bundled `./mvnw`)
- A **Supabase** project with the Google auth provider turned on
- A **Google Cloud** project with:
  - the Sheets API and Drive API turned on
  - a **service account** (for Sheets) that has access to the class spreadsheet
  - an **OAuth client** plus a **refresh token** (for reading team documents from Drive; see `OAuthCredentialConfig.java`)
- A **Google Sheet** containing these tabs:
  - `Teachers` — `A: email`, `B: name`
  - `Students` — `A: email`, `B: name`, `C: section`, `D: team code`
  - `Submissions` — form responses (`A2:K`: timestamp, …, name, section, team, SRS, SDD, SPMP, STD, Proposal, GitHub link)
- An **OpenAI API key**
- *(Optional)* a **GitHub personal access token**, which raises the API rate limit when ingesting repositories

## Local Setup

### 1. Backend

Create `backend/src/main/resources/application-secrets.properties`. Git ignores this file, and the backend loads it automatically:

```properties
# Database (Supabase pooler)
spring.datasource.url=jdbc:postgresql://<project-host>.pooler.supabase.com:5432/postgres?sslmode=require
spring.datasource.username=postgres.<project-ref>
spring.datasource.password=<db-password>

# Supabase Auth: needed to validate JWTs on /api/synctrace/**
app.supabase.project-url=https://<project-ref>.supabase.co

# Google Sheets (allowlist, roster, submissions)
app.google.spreadsheet-id=<google-sheet-id>
# Provide ONE of these (or place google-service-account.json in src/main/resources/)
app.google.service-account-json=
app.google.service-account-json-base64=<base64-service-account-json>

# Google OAuth (Drive: reading team documents)
app.google.oauth.client-id=<google-oauth-client-id>
app.google.oauth.client-secret=<google-oauth-client-secret>
app.google.oauth.refresh-token=<google-oauth-refresh-token>

# CORS
app.cors.allowed-origins=http://localhost:5173

spring.jpa.show-sql=false
```

Run it:

```bash
cd backend
./mvnw spring-boot:run        # http://localhost:8080
```

If required settings are missing, the backend prints one consolidated **SYNCTRACE CONFIGURATION WARNING** block at startup that lists everything to fix.

### 2. Frontend

Create `frontend/.env.local` (see `frontend/.env.example`):

```
VITE_API_BASE_URL=http://localhost:8080/api
VITE_SUPABASE_URL=https://<project-ref>.supabase.co
VITE_SUPABASE_ANON_KEY=<supabase-anon-key>
VITE_GOOGLE_CLIENT_ID=<google-oauth-client-id>
```

Run it:

```bash
cd frontend
npm install
npm run dev                   # http://localhost:5173
```

In Supabase → Authentication → URL Configuration, add `http://localhost:5173` as a redirect URL.

### 3. In-app configuration (System Settings)

Some runtime settings live in the database (`system_settings` table) instead of environment variables:

| Key | Where to set it | Purpose |
|-----|-----------------|---------|
| `ACTIVE_AI_PROVIDER`, `OPENAI_API_KEY`, `OPENAI_MODEL` | Teacher dashboard → Settings | Goal/component extraction, mapping suggestions, recommendations |
| `GITHUB_TOKEN` | Add a row directly in `system_settings` (it is not in the UI yet) | Higher GitHub API rate limits during ingestion |
| `GOOGLE_SHEET_ID`, `GOOGLE_RESPONSES_RANGE`, `COL_INDEX_*` | Seeded on every startup by `SettingsInitializer` | Submission sheet location and column layout (document and GitHub links per team) |

> **Note:** `SettingsInitializer` overwrites the `GOOGLE_SHEET_ID` and column-index rows with hard-coded values **on every startup**. To point submission sync at a different sheet, change the values in `SettingsInitializer.java`. Editing the database rows does not stick across restarts.

### 4. Tests and lint

```bash
cd backend && ./mvnw test
cd frontend && npm run lint
```

## Deployment

### Backend on Render (Docker)

`render.yaml` defines a Docker web service (`rootDir: backend`, health check `GET /api/settings`). The `backend/Dockerfile` builds the jar with Maven, then runs it on a JRE 21 image with `-Duser.timezone=GMT+08:00`, because timestamps are stored as zone-less local times.

1. In Render, create a **Blueprint** from this repo, or a Docker web service with root directory `backend`.
2. Set these environment variables (`backend/.env.render.example` is a starting point):

| Variable | Required | Notes |
|----------|----------|-------|
| `SPRING_DATASOURCE_URL` | yes | Supabase pooler JDBC URL, with `?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | yes | e.g. `postgres.<project-ref>` |
| `SPRING_DATASOURCE_PASSWORD` | yes | |
| `SUPABASE_PROJECT_URL` | yes | `https://<project-ref>.supabase.co`; without it, every `/api/synctrace/**` call fails authentication |
| `GOOGLE_SHEET_ID` | yes | Class spreadsheet (allowlist and roster) |
| `GOOGLE_SERVICE_ACCOUNT_JSON_BASE64` | yes* | *Or `GOOGLE_SERVICE_ACCOUNT_JSON` |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` / `GOOGLE_REFRESH_TOKEN` | yes | Drive access to team documents |
| `CORS_ALLOWED_ORIGINS` | yes | Comma-separated, e.g. `https://<app>.vercel.app,http://localhost:5173` |
| `SPRING_JPA_SHOW_SQL` | no | Defaults to `false` |

3. Deploy, then open `https://<service>.onrender.com/api/settings` to check that the service responds.

### Frontend on Vercel

1. Import the repo into Vercel with **Root Directory** set to `frontend`. Vercel detects Vite automatically: build `npm run build`, output `dist`.
2. Set the environment variables: `VITE_API_BASE_URL` (`https://<service>.onrender.com/api`), `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`, and `VITE_GOOGLE_CLIENT_ID`.
3. `frontend/vercel.json` rewrites every path to `index.html`, so deep links such as `/synctrace/group/TEAM-01` work.

### After deploying

- Add the Vercel URL to `CORS_ALLOWED_ORIGINS` on Render.
- Add the Vercel URL to Supabase → Authentication → **Site URL / Redirect URLs**.
- Add the Vercel URL to the Google OAuth client's authorized JavaScript origins and redirect URIs.
- Sign in as a teacher and set the OpenAI key and model in **Settings**.

## Group Details

| Role | Name |
|------|------|
| Backend Developer | Baritua, Carl Gabriel |
| Frontend Developer | Cordero, Camila Rose |
| AI Engineer | Delposo, Kerby |
| Documentation Lead | Temperatura, Sharbelle |
| Project Manager | Villadarez, Niña Nicole |
