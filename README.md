# SyncTrace

**SyncTrace: An Intelligent Project Continuity and Traceability Assistant**

SyncTrace continues the **IEEE Docs Evaluator** (document evaluation, Google Drive/Sheets, AI review) and the **MetaDoc**-style focus on project continuity — linking proposal intent through engineering artifacts to implementation.

It is a web-based assistant for software engineering capstone projects: teachers and students establish and monitor traceability between SMART goals, IEEE document components, and GitHub source code.

## Lineage

| Predecessor | What SyncTrace reuses / extends |
|---|---|
| **IEEE Docs Evaluator** | Auth, evaluation history, Drive document ingest, Sheets class list, AI providers, teacher/student dashboards |
| **MetaDoc** | Continuity and traceability intent across the software lifecycle (goals → docs → code) |

SyncTrace adds goal-first mapping, continuity gap detection, GitHub implementation components, and audit export on top of that shared stack.

## Adviser traceability model

The matrix **begins with SMART goals from the project proposal**:

1. **Extract** GENERAL and SPECIFIC objectives from the proposal (GENERAL → modules; SPECIFIC → functions/transactions).
2. **Directly translate** each goal into typed artifacts (safest path — teacher confirms mappings):
   - **SRS** — use case, activity, wireframe, context, data flow, …
   - **SDD** — class, sequence, UI, data model, non-OO, …
   - **SPMP** — tasks, deliverables, milestones, …
   - **STD** — test design, test case, test logs, …
   - **Implementation** — OO / non-OO units from the team GitHub repository (GitHub API ingest)
3. **Verify** coverage in the interactive matrix, detect continuity gaps, and generate diagnostics/recommendations.

```
Proposal SMART Goals
  ├── GENERAL objective  →  modules (SRS/SDD/SPMP/STD/Impl)
  │     └── SPECIFIC objective  →  functions / transactions
  └── Traceability matrix + continuity gaps
```

## Features

- GitHub repository integration (trees + raw file ingest)
- Source code preprocessing and Implementation component viewer
- Traceability mapping (manual + AI suggested)
- Continuity verification and gap detection
- AI diagnostic explanations and corrective recommendations
- Interactive traceability matrix (goal-first)
- Teacher traceability dashboard
- Student traceability results (roadmap)
- Project readiness monitoring
- Audit report generation and export

## Traceability Workflow

```
SMART Goal (from proposal)
    ├── SRS Components
    ├── SDD Components
    ├── SPMP Components
    ├── STD Components
    └── Implementation Components (GitHub)
```

Users directly map SMART Goals to their corresponding software engineering document components and source code implementation components. SyncTrace then verifies the completeness of the traceability network, detects continuity gaps, and generates diagnostic feedback and recommendations.

## Tech Stack

| Layer | Technology |
|-------|------------|
| Frontend | React 19 + Vite |
| Backend | Spring Boot 4 (Java 21) |
| Database | Supabase (PostgreSQL) |
| Auth | Google OAuth 2.0 via Supabase |
| Storage | Google Drive API |
| Spreadsheet | Google Sheets API |
| AI Provider | OpenAI API |
| Source control | GitHub API |

## Project Structure

```
SyncTrace/
├── backend/
│   └── src/main/java/com/ieee/evaluator/synctrace/
│       ├── config/
│       ├── controller/
│       ├── model/
│       ├── repository/
│       └── service/
│
├── frontend/
│   └── src/synctrace/
│       ├── components/
│       ├── pages/
│       ├── hooks/
│       └── styles/
│
└── README.md
```

## Frontend Setup

```bash
cd frontend
npm install
npm run dev
```

Create a `.env.local` file in the `frontend/` directory:

```
VITE_API_BASE_URL=http://localhost:8080/api
VITE_SUPABASE_URL=https://your-project-ref.supabase.co
VITE_SUPABASE_ANON_KEY=your_supabase_anon_key
VITE_GOOGLE_CLIENT_ID=your_google_client_id
```

## Backend Setup

```bash
cd backend
mvn spring-boot:run
```

For local development, create `backend/src/main/resources/application-secrets.properties` and fill in real values:

```properties
# Database (Supabase Pooler)
spring.datasource.url=jdbc:postgresql://<project-host>.pooler.supabase.com:5432/postgres?sslmode=require
spring.datasource.username=postgres.<project-ref>
spring.datasource.password=<db-password>

# Google integrations
app.google.spreadsheet-id=<google-sheet-id>
# Provide ONE only:
app.google.service-account-json=
app.google.service-account-json-base64=<base64-service-account-json>

# Google OAuth (used for Drive — reading student submission files)
app.google.oauth.client-id=your_google_client_id
app.google.oauth.client-secret=your_google_client_secret
app.google.oauth.refresh-token=your_google_refresh_token

# CORS
app.cors.allowed-origins=http://localhost:5173
app.openrouter.http-referer=http://localhost:5173
app.openrouter.app-title=IEEE Docs Evaluator (Local)

# Optional logging
spring.jpa.show-sql=false
```

Run tests:

```bash
./mvnw test
```

## Group Details

| Role | Name |
|------|------|
| Backend Developer | Baritua, Carl Gabriel |
| Frontend Developer | Cordero, Camila Rose |
| AI Engineer | Delposo, Kerby |
| Documentation Lead | Temperatura, Sharbelle |
| Project Manager | Villadarez, Niña Nicole |
