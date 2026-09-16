---
name: project-architecture
description: Design or update meet-me-server architecture, integration boundaries, persistence, authentication, AI usage, or AWS deployment while preserving the project's hexagonal architecture and decision records.
---

# Project Architecture

Use this skill for architecture planning, architecture-affecting implementation, or changes to external integrations and deployment.

## Grounding

Read the following before deciding or editing:

1. `docs/PRD.md`
2. `docs/ARCHITECTURE.md`
3. `docs/ADR.md`
4. `.agents/rules/architecture.md`
5. `.agents/rules/adr.md`
6. `.agents/rules/user-intervention.md`

Read `.agents/rules/development.md` only when the task also changes development or verification workflow.

## Workflow

1. Separate already accepted decisions from `TBD` items.
2. Preserve the domain-to-adapter dependency rule and the deterministic matching boundary.
3. Define the affected ports, adapters, data owner and failure boundary before implementation.
4. Do not silently resolve a `TBD` when alternatives materially change cost, operations, security or data compatibility.
5. Split external setup into `[AGENT]`, `[USER]`, and `[SHARED]` work. Before dependent work, give the user an actionable step-by-step guide when identity, consent, billing, administrator permission, or real secret values are required.
6. Update `docs/ARCHITECTURE.md` when the system structure changes.
7. Add an ADR only when the decision meets `.agents/rules/adr.md` criteria.
8. Verify document consistency and run the smallest tests that prove the affected boundary.

## Project Constraints

- The repository contains backend code only.
- Use Spring Boot with Kotlin and PostgreSQL.
- Manage PostgreSQL schema changes with Flyway migrations.
- Keep permanent business data authoritative in PostgreSQL.
- Keep AI and external provider SDK types inside outbound adapters.
- Never delegate final schedule matching to an LLM.
- Never commit credentials, tokens or user-sensitive payloads.
