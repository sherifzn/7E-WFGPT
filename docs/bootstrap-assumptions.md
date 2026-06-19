# Bootstrap Assumptions and Limits

## Scope

Task 001 creates repository foundations only. It does not implement workflow definitions, workflow execution, business rules, assignments, SLAs, Oracle integration, AI model calls, identity federation, document storage, or production deployment.

## Technology assumptions

- Java is used for the backend skeleton because the initial constraints allow Java or Kotlin. The final primary backend language requires an ADR.
- TypeScript and React are used for the frontend skeleton because the initial constraints allow TypeScript and React. The final framework and design system require an ADR.
- Maven is used for Java modules because it is available locally and keeps the bootstrap lightweight.
- npm workspaces are used for frontend tooling and root quality gates.
- GitHub Actions is used for pull-request checks in this repository. Hosting and CI/CD strategy remain unresolved for production.

## Security and privacy assumptions

- No production systems, credentials, or service identities are used.
- No real customer, identity, legal, financial, evidence, or Oracle payload data is stored.
- The health endpoint has no external dependencies.
- Integration surfaces are mocked or absent until approved typed connector interfaces are designed.
- Secret scanning is intentionally conservative and runs against tracked source files.

## Unresolved ADRs

- Primary backend language: Java or Kotlin.
- Frontend framework and design system.
- Core workflow runtime engine.
- BRE and DMN implementation.
- Event bus technology.
- Runtime database.
- Oracle integration pattern.
- Identity federation implementation.
- AI Gateway and model routing.
- Observability stack.
- Secrets and key management.
- Cloud, hybrid, or on-premises deployment.

## Known limitations

- The backend health endpoint uses the JDK HTTP server only for bootstrap validation.
- Structured logging writes JSON to stdout with local redaction support, but no observability backend is selected.
- Local dependency scanning verifies the generated inventory, runs npm audit, and resolves the Maven dependency tree. Pull-request CI also runs GitHub dependency review for vulnerability checks.
- Architecture tests enforce initial forbidden dependency and integration markers only; they should expand as modules become real.
