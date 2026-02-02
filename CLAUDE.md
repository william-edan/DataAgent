# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DataAgent is an enterprise-grade intelligent data analysis AI system built on Spring AI Alibaba. It combines Text-to-SQL, Python deep analysis, intelligent report generation, and RAG enhancement. The system supports MCP (Model Context Protocol) and can serve as an MCP server for Claude Desktop integration.

## Build & Development Commands

### Backend (Java/Maven)

```bash
# Build project (skip tests)
make build

# Run tests
make test

# Format code (Spring Java Format)
make format-fix

# Check code formatting
make format-check

# Run spotless formatter
make spotless-apply

# Run checkstyle
make checkstyle-check

# Run linters (yaml, codespell, newline)
make lint

# Start backend server
cd data-agent-management && ./mvnw spring-boot:run
```

### Frontend (Vue 3/Vite)

```bash
cd data-agent-frontend

npm install          # Install dependencies
npm run dev          # Start dev server (localhost:3000)
npm run build        # Production build
npm run lint         # ESLint with auto-fix
npm run lint:check   # ESLint check only
npm run format       # Prettier format
npm run format:check # Prettier check
npm run type-check   # TypeScript check
```

## Architecture

### Project Structure

```
DataAgent/
├── data-agent-management/     # Backend (Spring Boot 3.4.8 + Java 17)
│   └── src/main/java/com/alibaba/cloud/ai/dataagent/
│       ├── controller/        # REST API endpoints
│       ├── service/           # Business logic (17+ service modules)
│       ├── workflow/          # StateGraph-based AI orchestration
│       │   ├── node/          # Workflow nodes (15 types)
│       │   └── dispatcher/    # Node dispatchers
│       ├── connector/         # Database connectors (MySQL, PostgreSQL, SQL Server, Dameng, H2)
│       │   └── impls/         # Database-specific implementations
│       ├── entity/            # JPA/MyBatis entities
│       └── mapper/            # MyBatis mappers
├── data-agent-frontend/       # Frontend (Vue 3 + Vite + Element Plus)
│   └── src/
│       ├── components/        # Vue components
│       ├── views/             # Page components
│       └── services/          # API client layer
└── docs/                      # Documentation
```

### Core Architecture Pattern

The system uses a StateGraph workflow for AI-driven orchestration:

1. **IntentRecognitionNode** → Understand user intent
2. **EvidenceRecallNode** → Retrieve relevant context (RAG)
3. **SchemaRecallNode** → Find relevant database tables
4. **FeasibilityAssessmentNode** → Check query feasibility
5. **PlannerNode** → Generate execution plan
6. **PlanExecutorNode** → Execute with human feedback gate
7. **SqlGenerateNode** → Generate SQL
8. **SqlExecuteNode** → Execute on business database
9. **PythonGenerateNode/ExecuteNode** → Python analysis
10. **HumanFeedbackNode** → Collect user feedback

### Key Service Modules

- `llm` - LLM abstraction (OpenAI-compatible APIs)
- `nl2sql` - Natural Language to SQL conversion
- `vectorstore` - Vector store abstraction
- `hybrid` - Hybrid retrieval (keyword + semantic)
- `code` - Python execution (Docker/Local/AI simulation)
- `datasource` - Multi-database connection pooling
- `knowledge` - Knowledge base management (Document, Q&A, FAQ)
- `mcp` - MCP server integration

### Database Architecture

- **Management DB**: MySQL 5.7+ for system configuration
- **Business DB**: User's data sources (multi-database supported)
- **Vector Store**: Elasticsearch (optional, defaults to in-memory)

## Configuration

Main config: `data-agent-management/src/main/resources/application.yml`

Key environment variables:
- `DATA_AGENT_DATASOURCE_URL`, `DATA_AGENT_DATASOURCE_USERNAME`, `DATA_AGENT_DATASOURCE_PASSWORD`
- `OSS_ACCESS_KEY_ID`, `OSS_ACCESS_KEY_SECRET`, `OSS_ENDPOINT`, `OSS_BUCKET_NAME` (for OSS file storage)

## Code Quality

- Java: Spring Java Format + Checkstyle + Spotless
- Frontend: ESLint + Prettier
- All code must pass `make format-check` before commit
