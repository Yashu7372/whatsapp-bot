# Project Control Service

Fresh Spring Modulith implementation of the frozen Project Control v2.1 north star.

## Implementation rules

- This application is independent from the legacy WhatsApp/CRM backend in the repository.
- The legacy application is a behavior/reference source only; this service does not depend on it.
- Preserve business truth, not old package structure.
- Start with deterministic relational domain foundations.
- No authentication/authorization implementation in the first domain slices, but identity and isolation boundaries must remain representable.
- No microservices, Kafka, RabbitMQ, graph database, BPM engine, policy DSL, AI, or compatibility adapters in the foundation.
- Project Scope starts as a simple parent/child tree.
- Scope capabilities are configuration records, not runtime plugins.
- Client is not Tenant/Workspace. Organizations are global identities and may participate in projects across workspaces.
- Do not create a domain entity merely because a company gives a workflow a business name (for example ITR, MIR, design review, or tender evaluation). Model it first as Scope + Capability + Generic Workflow. Introduce a dedicated domain only when it owns durable business data/invariants independent of the workflow.
- Typed foreign keys and typed domain tables remain authoritative. Do not create generic target-type/target-id relationships until both sides have real domain meaning and integrity can be enforced.

## Foundation slice 01 - Project context

The first slice proves:

`Workspace -> Project`

`Organization -> Project Participation -> Project Scope -> Scope Capability`

A single organization can participate in multiple projects, under different clients/workspaces, with different project roles and scope assignments.

## Foundation slice 02 - Document register and revisions

The second slice proves:

`Project -> optional Project Scope -> Document -> immutable Revision history`

Documents remain independent business resources. They are not workflow containers and do not own approval/ITR/inspection state.

Document numbering is configured by `seriesCode`, not by document type alone, so one project can have multiple numbering schemes for the same document type. A generated document records the series code used; externally numbered documents do not require a series.

Only stable document fields are first-class columns. Variable project/company metadata is stored in `metadataJson`; later configuration can define schemas or UI fields without expanding the core document table for every company convention.

Generic document-to-arbitrary-resource links are intentionally deferred. Evidence relationships will be introduced only when the owning subject/workflow/domain exists and both sides can be validated.

## Technology baseline

- Java 21
- Spring Boot 4.1.1
- Spring Modulith 2.1.1
- PostgreSQL
- Flyway
- Spring Data JPA
- Maven

Java 21 is intentionally retained for broad local compatibility while using the current Spring Boot/Modulith baseline. A JDK upgrade can be evaluated independently later.

## Run locally

Create a PostgreSQL database named `project_control` and optionally set:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/project_control
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
```

Then:

```bash
mvn spring-boot:run
```

## Verify

```bash
mvn clean verify
```

The test profile uses H2 in PostgreSQL compatibility mode so the foundation tests remain runnable without Docker. CI also verifies the migrations and mappings against PostgreSQL.
