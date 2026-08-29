# Project Control Service

Fresh Spring Modulith implementation of the frozen Project Control v2.1 north star.

## Implementation rules

- This application is independent from the legacy WhatsApp/CRM backend in the repository.
- The legacy application is a behavior/reference source only; this service does not depend on it.
- Preserve business truth, not old package structure.
- Start with deterministic relational domain foundations.
- No authentication/authorization implementation in the first domain slice, but identity and isolation boundaries must remain representable.
- No microservices, Kafka, RabbitMQ, graph database, BPM engine, policy DSL, AI, or compatibility adapters in the foundation.
- Project Scope starts as a simple parent/child tree.
- Scope capabilities are configuration records, not runtime plugins.
- Client is not Tenant/Workspace. Organizations are global identities and may participate in projects across workspaces.

## Foundation slice 01

The first slice proves:

`Workspace -> Project`

`Organization -> Project Participation -> Project Scope -> Scope Capability`

A single organization can participate in multiple projects, under different clients/workspaces, with different project roles and scope assignments.

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

The test profile uses H2 in PostgreSQL compatibility mode so the foundation tests remain runnable without Docker. PostgreSQL remains the production source of truth.
