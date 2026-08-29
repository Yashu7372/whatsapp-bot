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

### Foundation 07 — Financial control, billing and derived cash flow

The financial foundation preserves the frozen separation between project context, cost control and commercial value.

`Project Scope != Cost Breakdown Structure (CBS)`

Project Scope remains the operational drill-down tree. CBS is an independent hierarchical financial structure. `cost_node_scope_links` provides the deliberate typed mapping between the two so one cost node can support one or more project scopes without turning either model into the other.

Organization-private internal cost is independent from external contract/commercial truth:

`CBS -> Budget Version -> Budget Lines`

`CBS Node -> Commitment -> Actual Cost + Remaining Forecast`

`Budget Exposure = Actual + Open Commitment`

`Open Commitment = Commitment - posted actual already recognized against that commitment`

The first slice intentionally does not create accrual/reservation ledgers until a concrete business use case requires them. Budget control decisions are append-only and the authoritative commitment command executes the budget gate inside the same transaction.

Commercial billing remains a separate chain:

`Contract -> Contract Item -> Valuation -> Payment Application / IPC -> Certification -> Payment`

Milestone, lump-sum, percentage, time-based and other non-quantity valuations can use a controlled `DocumentRevision` as supporting evidence. Quantity-rate valuation is measurement-backed as described in Foundation 08; accepted quantity is never accepted as a free-form billing value.

The project financial drill-down is a read-model composition rather than another ledger:

`Project -> Scope -> direct cost + verification/measurement facts`

`Project -> CBS -> Cost Node -> linked Scopes -> budget/exposure`

`Project -> Contract -> Valuation -> IPC -> Payment`

Contract values are never added to organization-private internal cost totals. The caller must choose an explicit organization perspective.

Cash flow is also derived, not authoritative storage. Monthly views expose posted internal cost, remaining forecast, certified receivable/payable and actual payment cash in/out separately. Posted accounting cost is not treated as cash movement.

### Foundation 08 — Verification, accepted measurement and payment provenance

Verification is an optional scope capability and remains independent from any ITR/MIR-specific domain model.

`Project Scope -> Verification Package -> Subject Items + Controlled Evidence`

A package records the submitting organization/user, claimed progress or quantity, immutable `DocumentRevision` evidence, and a typed link to the existing generic workflow. Decisions are append-only and preserve the acting user, acting organization, workflow instance, comments and subject version.

Supported terminal outcomes include:

- `ACCEPTED`
- `ACCEPTED_WITH_COMMENTS`
- `PARTIALLY_ACCEPTED`
- `REJECTED`
- `RETURNED_FOR_REWORK`
- `MORE_EVIDENCE_REQUESTED`

A corrected/resubmitted package uses `parent_package_id`; the earlier attempt is never overwritten.

For measurable work:

`Verification Decision -> Measurement`

Measurement preserves submitted quantity, measured quantity, accepted quantity, rejected/rework quantity, unit, measurement period, verifier, package/item/decision provenance and version. Only accepted quantity from a completed accepted/partially accepted verification can be used commercially.

Quantity-rate valuation is deterministic:

`Accepted Measurement.quantity x Contract Item.rate = Valuation Line.currentValue`

The caller cannot submit an arbitrary quantity value or substitute a document revision for accepted measurement truth. A typed `valuation_lines.measurement_id` foreign key and `(contract_item_id, measurement_id)` uniqueness prevent one accepted measurement from being valued twice for the same contract item.

The completed typed reverse trace is:

`Payment -> Payment Application / IPC -> IPC Line -> Valuation Line -> Accepted Measurement -> Verification Package -> Subject Work/Deliverable Reference -> Controlled Evidence / Document Revision -> Verification Decisions -> Users / Organizations -> Generic Workflow`

Non-quantity valuation remains valid without inventing measurement: milestone/lump-sum/percentage/time-based valuation can trace directly to its controlled supporting `DocumentRevision`.

The canonical integration proof demonstrates the frozen CHW scenario:

`320 m submitted -> 300 m accepted + 20 m rework -> child verification accepts 20 m -> 320 m accepted in two immutable measurements -> AED 400/m valuation -> IPC -> certification -> payment -> reverse provenance trace`

The verification-to-workflow foreign key is deferrable until transaction commit because workflow creation is JPA-backed while the typed verification link is JDBC-backed in the same transaction. Referential integrity is still enforced by PostgreSQL after the persistence context flushes.

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
