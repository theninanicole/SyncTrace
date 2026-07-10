# SyncTrace

**SyncTrace: An Intelligent Project Continuity and Traceability Assistant**

SyncTrace is a web-based project continuity and traceability assistant that extends the IEEE Docs Evaluator by introducing traceability, continuity verification, GitHub source code integration, and audit reporting for software engineering capstone projects.

The system enables students and teachers to establish and monitor traceability relationships between SMART Goals, software engineering artifacts, and implementation components throughout the software development lifecycle.

## Features

- GitHub repository integration
- Source code preprocessing
- Traceability mapping
- Continuity verification
- Continuity gap detection
- AI diagnostic explanations
- AI-generated corrective recommendations
- Interactive traceability matrix
- Teacher traceability dashboard
- Student traceability results
- Project readiness monitoring
- Audit report generation and export

## Traceability Workflow

SyncTrace follows a goal-based traceability model.

```
SMART Goal
    ├── SRS Components
    ├── SDD Components
    ├── SPMP Components
    ├── STD Components
    └── Implementation Components
```

Users directly map SMART Goals to their corresponding software engineering document components and source code implementation components.

SyncTrace then verifies the completeness of the traceability network, detects continuity gaps, and generates diagnostic feedback and recommendations.

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
| PDF Rendering | Apache PDFBox |
| Text Extraction | Apache Tika |

## Project Structure

```
SynctraceV3/
├── Backend/
│   └── docs-evaluator/
│       └── src/main/java/com/ieee/evaluator/synctrace/
│           ├── config/
│           ├── controller/
│           ├── model/
│           ├── repository/
│           └── service/
│
├── Frontend/
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
cd Frontend
npm install
npm run dev
```

Create a `.env.local` file in the `Frontend/` directory:

```
VITE_API_BASE_URL=http://localhost:8080/api
VITE_SUPABASE_URL=https://your-project-ref.supabase.co
VITE_SUPABASE_ANON_KEY=your_supabase_anon_key
VITE_GOOGLE_CLIENT_ID=your_google_client_id
```

## Backend Setup

```bash
cd Backend/docs-evaluator
mvn spring-boot:run
```

For local development, create `Backend/docs-evaluator/src/main/resources/application-secrets.properties` and fill in real values:

```properties
# Database (Supabase Pooler)
spring.datasource.url=jdbc:postgresql://<project-host>.pooler.supabase.com:6543/postgres?sslmode=require
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

## Local Development Workflow

- **Do not edit deployment files for local secrets.**
- Put local backend secrets only in `Backend/docs-evaluator/src/main/resources/application-secrets.properties` (already git-ignored).
- Put local frontend secrets only in `Frontend/.env.local` (already git-ignored).

Start services locally:

```bash
# Terminal 1
cd Backend/docs-evaluator
./mvnw spring-boot:run

# Terminal 2
cd Frontend
npm install
npm run dev
```

## Group Details

| Role | Name |
|------|------|
| Backend Developer | Baritua, Carl Gabriel |
| Frontend Developer | Cordero, Camila Rose |
| AI Engineer | Delposo, Kerby |
| Documentation Lead | Temperatura, Sharbelle |
| Project Manager | Villadarez, Niña Nicole |
