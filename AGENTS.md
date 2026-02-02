# Repository Guidelines

## Project Structure & Module Organization
- `data-agent-management/`: Spring Boot backend (Java 17) with controllers, services, workflow nodes, and database connectors.
- `data-agent-frontend/`: Vue 3 + Vite frontend UI.
- `docs/`: architecture, developer guide, quick start, and advanced feature docs.
- `CI/`: local CI tooling (make targets, checkstyle, lint config).
- `docker-file/`: Dockerfiles and compose configs.
- `img/`: documentation images and diagrams.
- Tests live in `data-agent-management/src/test/java` and follow `*Test.java` naming.

## Build, Test, and Development Commands
- `make build`: build backend (skips tests).
- `make test`: run backend tests.
- `make format-check` / `make format-fix`: check or apply Spring Java format.
- `make checkstyle-check`: run Java checkstyle.
- `make lint`: run repo linters (yaml, codespell, newline).
- `cd data-agent-management && ./mvnw spring-boot:run`: start backend (default port 8065).
- `cd data-agent-frontend && npm install && npm run dev`: start frontend (default port 3000).

## Coding Style & Naming Conventions
- Java follows Spring Java Format and Checkstyle; Spotless removes unused imports.
  - Recommended: `mvn clean package` (formats in Spring style).
  - Targeted checks: `mvn checkstyle:check`, `mvn spotless:apply`.
- Frontend uses ESLint + Prettier with 2-space indentation, single quotes, semicolons, and 100-char line width.
  - Useful: `npm run lint`, `npm run format`, `npm run type-check`.
- Keep classes and components in `UpperCamelCase`, and use `kebab-case` for file names where applicable.

## Testing Guidelines
- Backend tests use JUnit Jupiter + Spring Boot Test; some integration tests use Testcontainers.
- Run all backend tests via `make test` or `cd data-agent-management && ./mvnw test`.
- No explicit coverage threshold is documented; include tests for new features or bug fixes.

## Commit & Pull Request Guidelines
- Commit messages follow a Conventional Commits style: `type(scope): message`.
  - Examples: `feat(AgentRun): add report format toggle`, `fix(datasource): add PostgreSQL schema support`.
- Before submitting a PR, rebase on the latest `main`.
- PRs should follow `.github/PULL_REQUEST_TEMPLATE.md` and include:
  - What/why, how, verification steps, and any review notes.
- Ensure code passes `make format-check` before commit.

## Configuration & Security Notes
- Backend config: `data-agent-management/src/main/resources/application.yml`.
- Frontend proxy targets are in `data-agent-frontend/vite.config.js`.
- See `SECURITY.md` for disclosure guidance.

## Agent-Specific Instructions
- For detailed architecture and command references, read `CLAUDE.md`.
