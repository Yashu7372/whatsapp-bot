# Project Control Service

Fresh Spring Modulith implementation of the frozen Project Control v2.1 north star.

## Implementation rules

- This application is independent from the legacy WhatsApp/CRM backend in the repository.
- Preserve business truth, not old package structure.
- Keep business generalization in relational data/configuration; do not add technical engines prematurely.
- Project Scope starts as a simple parent/child tree.
- Scope capabilities are configuration records, not runtime plugins.
- Client is not Tenant/Workspace. Organizations are global identities and may participate in projects across workspaces.
- A business workflow name such as ITR/MIR/design review is not automatically a domain entity. Model it first as Scope + Capability + Generic Workflow.
- Typed foreign keys and deliberate typed link tables remain authoritative.

## Implemented foundations

### Foundation 01 — Project context

`Workspace -> Project`

`Organization -> Project Participation -> Project Scope -> Scope Capability`

One organization can participate in multiple projects/workspaces with different project roles and scope assignments.

### Foundation 02 — Document register

`Project -> optional Project Scope -> Document -> immutable Revision history`

Documents own document truth, not workflow state. Numbering uses configurable project `seriesCode`; optional company/project metadata is stored in `metadataJson`.

### Foundation 03 — Generic scope workflow

`Project -> Workflow Definition -> Steps`

`Project Scope -> Capability + explicit Workflow Binding`

`Workflow Instance -> Step Visits -> Actions / Comments / Return / Reject / Completion`

The real ITR-style sequence is configuration only:

`Site Team -> QCE -> QC/DC -> Consultant Inspector -> Consultant RE`

There is deliberately no ITR/InspectionRequest domain just because the process has that name.

### Foundation 04 — Identity and contextual access

`User -> Organization Membership -> Project Participation -> Scope Assignment -> ActorContext -> ProjectAccessService`

`ProjectAccessService` is the business authorization choke point. Workspace, organization, project and scope relationships determine access; business roles are not global identities.

### Foundation 05 — Document workflow + PDF proof

Documents can be linked to workflow instances through a typed relation with foreign keys on both sides. Local PDF upload/view proves immutable revision content and access-controlled content retrieval.

### Foundation 06 — Authentication + workflow-step responsibility

Spring Security now authenticates local users with username/password, BCrypt, a server-side session and CSRF protection. Protected APIs no longer accept `X-Project-Control-User`; the user comes from the authenticated principal.

Document creation verifies that a non-admin actor actually represents the requested originator organization in that project.

A workflow step may define assignment criteria in `assignmentJson`. Supported criteria are:

- `responsibility` / `responsibilityCodes`
- `partyRole` / `partyRoles`
- `accessLevel` / `accessLevels`
- `workspaceRole` / `workspaceRoles`
- `organizationId` / `organizationIds`

Categories combine with AND semantics; multiple values in one category use OR semantics. `{}` adds no step-specific restriction beyond normal workflow action access. Project Admin is an explicit override. Unsupported assignment rules fail closed.

The integration proof verifies that Site Team cannot execute QCE, Consultant Inspector cannot execute RE final approval, and a scope-only Viewer can see MEP resources without gaining Civil scope visibility.

## Local authentication

Run with the `local` profile. The service bootstraps local credential accounts. All use password:

```text
Project123!
```

Accounts:

```text
admin@local.demo
site@local.demo
qce@local.demo
qcdc@local.demo
inspector@local.demo
re@local.demo
viewer@local.demo
```

This is a real Spring Security session for local testing. The credential source can later be replaced by enterprise OIDC/SSO without changing `ActorContext` or `ProjectAccessService`.

## Technology baseline

- Java 21
- Spring Boot 4.1.1
- Spring Modulith 2.1.1
- PostgreSQL
- Flyway
- Spring Data JPA
- Spring Security
- Maven

## Run locally without Docker

```bash
mvn -Dspring-boot.run.profiles=local spring-boot:run
```

The local profile uses persistent H2. CI verifies migrations/mappings and the full test suite against PostgreSQL 16.

If your local database is an older foundation snapshot, stop the app and delete `project-control-local.mv.db` before restarting.

## Verify

```bash
mvn clean verify
```
