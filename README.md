# Enterprise Workflow Platform

## Purpose

A configurable, AI-assisted workflow platform for business users. It supports deterministic workflow execution, reusable business rules, secure human-task management, prerequisite orchestration, governed knowledge, and progressive decision autonomy.

## Product areas

1. AI Process Studio
2. Workflow Control Plane
3. Workflow Runtime
4. Work Management Layer
5. Business Rules and Decision Service
6. Connector Gateway
7. AI Gateway
8. Decision Knowledge Platform
9. Governance and Monitoring Center

## First delivery target

The first vertical slice is the Property Key Handover process:

- front desk initiates the request
- Handover, Finance, and Legal checks execute in parallel
- missing inspection starts or correlates with an Inspection workflow
- Finance obtains outstanding amounts through a mocked Oracle connector
- all tasks use configurable assignment and SLA policies
- a BRE decision produces Approved, Exception Review, or On Hold
- the system generates a release authorization and notification
- every transition is audited

## Development rule

The first milestone is not a full low-code designer. It is a secure executable vertical slice plus the configuration contracts that a future designer will produce.

## Local bootstrap setup

Task 001 establishes repository foundations only. It includes a Java backend skeleton, TypeScript React frontend skeleton, shared contract module, architecture tests, local quality gates, and CI checks. It does not include workflow runtime behavior, business rules, Oracle connectivity, AI model calls, production credentials, or real customer data.

Task 002 adds immutable core domain contracts in a pure Java `domain` module and keeps serialization schemas in the separate `adapters` module. It still does not implement workflow execution, persistence, REST APIs, Oracle integration, identity integration, AI functionality, or production infrastructure.

Task 003 adds the first executable Property Key Handover slice behind explicit ports with synthetic adapters only. Design notes are in `docs/key-handover-slice.md`; it still avoids production Oracle, identity, AI, workflow-engine, database, REST, and cloud-infrastructure choices.

### Prerequisites

- Java 21 or newer
- Maven 3.9 or newer
- Node.js 22 or newer
- npm 10 or newer

### Install dependencies

```bash
npm install
```

### Run checks

```bash
npm run format
npm run lint
npm run test
npm run scan:secrets
npm run scan:deps
```

Run the full local gate with:

```bash
npm run validate
```

### Run locally

Backend health endpoint:

```bash
mvn -pl backend -am package
java -cp backend/target/classes:contracts/target/classes com.sevenewf.workflow.backend.BackendApplication
```

Frontend development shell:

```bash
npm run dev --workspace frontend
```

All fixtures and displayed data in this bootstrap are synthetic. External integrations are mocked or absent until typed connector contracts and ADRs are approved.
