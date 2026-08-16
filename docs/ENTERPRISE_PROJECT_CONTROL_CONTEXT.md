# Enterprise Project Control — Authoritative Implementation Context

## 1. Product priority

The repository started as a WhatsApp/CRM product and later accumulated document control, content generation and video-generation capabilities. The current product priority is **Enterprise Project Delivery & Document Control**.

For this phase:

1. Enterprise project delivery is the primary application experience.
2. WhatsApp remains an important communication, intake and notification channel.
3. Document Control, approvals, project controls, resources/time, commercial control and AI intelligence are one connected product.
4. Video generation, content studio, campaigns and social publishing are dormant/secondary. Their code is preserved but must not drive the main navigation or architecture.
5. Existing trusted implementations should be reused rather than duplicated.

The product is not merely a file repository. Its purpose is to connect the evidence chain:

`Project -> Stage -> Work Package -> Work Item -> Documents / People / Time / Equipment / Cost -> Approval / Certification -> IPC evidence -> Project intelligence`.

A document is therefore evidence/context around work; the project/work hierarchy is the primary navigation backbone.

---

## 2. Repositories and active branch

| Layer | Repository | Branch | Runtime |
|---|---|---|---|
| Backend | `Yashu7372/whatsapp-bot` | `feature/enterprise-document-control` | Java 21 / Spring Boot / PostgreSQL |
| Frontend | `Yashu7372/whatsapp-crm` | `feature/enterprise-document-control` | React / TypeScript / Vite |

The frontend API client defaults to `/api/v1` and attaches the JWT from local storage. 401 responses attempt token refresh and then return to login if refresh fails.

---

## 3. Access model — keep the two axes separate

### 3.1 Application access role

Current fixed application roles are:

- `ADMIN`
- `MANAGER`
- `REVIEWER`
- `VIEWER`

These are **authorization roles**, not job titles. Do not introduce Architect, Engineer, Accountant, QS, Worker, etc. as new values in this enum simply to describe a person's profession.

`tenant_users.job_title` and `tenant_users.department` now hold the real-world staff position. Work-item assignments provide the project/task responsibility.

### 3.2 Project party role

Organizations participate on a project as:

- `CLIENT`
- `CONSULTANT`
- `CONTRACTOR`
- `SUBCONTRACTOR`

A subcontractor can reference the participant that engaged it, preserving the chain of responsibility.

### 3.3 Effective authorization

Effective project access is evaluated from multiple facts:

`tenant membership + application role + organization + project participation + project party role + capability override + object/workflow assignment`.

Important current rule: an organization-attached `ADMIN` or `MANAGER` is **not** a tenant-wide administrator. Tenant administration is reserved for an ADMIN/MANAGER user with no organization assignment.

Default examples already implemented:

- all participating organizations can view a project/document at permitted security scope;
- contractor/subcontractor managers create/issue their organization's controlled documents;
- consultant reviewers perform technical review;
- client reviewers perform client approval;
- client/consultant managers can see project-level commercial data;
- contractor managers can see their organization's commercial/claim data;
- payment certification is client/consultant authority;
- marking an externally settled payment as paid is a client action.

Project-specific capability overrides can narrow or widen these defaults without changing role names.

---

## 4. Existing backend capability inventory

### Authentication / tenancy

- login / JWT / refresh token;
- tenant users;
- platform administrator concept;
- tenant features;
- feature catalog;
- feature-to-API route catalog;
- role permissions;
- centralized `FeatureAuthorizationInterceptor`.

### Projects and organizations

- projects;
- organizations;
- multi-party project participants;
- parent participant relationship for subcontractors;
- centralized project visibility and project capability authorization;
- document-number series per project/document type.

### Document Control

- project-aware documents;
- originator organization;
- controlled document number;
- document type / discipline / package / location;
- security classification (`PROJECT`, `ORGANIZATION`, `RESTRICTED`);
- revisions / current revision;
- issue purpose (`FOR_INFORMATION`, `FOR_REVIEW`, `FOR_APPROVAL`, `FOR_CONSTRUCTION`, `AS_BUILT`);
- comments;
- due/SLA dates;
- review return outcome;
- approved/evidenced work value;
- audit chain;
- object storage;
- optional zero-knowledge/encrypted metadata;
- malware scanning;
- external upload links;
- portal and WhatsApp intake channels.

### Document workflow

Workflow definitions are data-driven by document type. Approval steps support:

- authority type: internal review, technical review, client approval, commercial certification;
- assignment by user, organization or project party role;
- required/optional step;
- parallel group;
- SLA hours and due time;
- decision/comments/review outcome;
- workflow notifications via in-app/email/WhatsApp delivery infrastructure.

This is a strong foundation for a future generic approval engine; do not build a second unrelated document approval system.

### Transmittals

- project-scoped transmittals;
- sender organization;
- recipients;
- issue purpose;
- issued revisions;
- acknowledgement/closure state.

### Payment / IPC

The existing payment application service is an important control boundary:

1. a claimant is derived from the logged-in user's organization (except tenant-admin acting on behalf of a participant);
2. claimant must be a project participant;
3. a claim line references an approved project document;
4. a claim cannot exceed the document's approved/evidenced value;
5. the same document cannot be fully reused on several live claims;
6. claimant cannot certify its own claim;
7. only Client/Consultant authority can certify;
8. only Client authority can mark a certified application as externally paid;
9. payment actions are audited.

The platform records/certifies evidence and payment state. It does not itself transfer money.

### Resources, time and cost

- project resources (`PERSON`, `EQUIPMENT`, `MACHINE`, `VEHICLE`);
- resource rates;
- timesheet submission/approval;
- optional document link on timesheets;
- equipment usage;
- actual-cost ledger;
- organization versus project visibility;
- employee-visible hours separated from manager-visible rates/amounts.

### Project Controls

- participant contracts;
- commercial model;
- versioned budgets;
- cost-code budget lines;
- commitments;
- actual cost;
- variations;
- forecast snapshots;
- deterministic control KPIs / early warnings;
- commercial AI summary based on grounded project facts.

### WhatsApp

The WhatsApp webhook stack currently provides:

- inbound message parsing;
- tenant resolution by WhatsApp phone-number ID;
- conversation/contact registration;
- idempotency;
- AI replies;
- human-handoff state;
- interactive payload handling;
- lead signal extraction;
- inbound document download;
- security scan;
- creation of a Document Control record;
- outbound reply/audit.

Current limitation: generic inbound WhatsApp documents do not yet resolve a project/work-item context automatically. That should be added as a routing/resolution enhancement, not by making WhatsApp a second document repository.

---

## 5. New generalized delivery hierarchy (V42)

The missing business/navigation layer is now modeled explicitly.

### `project_stages`

Project lifecycle gates/stages. Stage names are data, not code. A full UAE construction template can include feasibility, concept, detailed design, authority approvals, procurement, construction, commissioning and handover; a smaller project can collapse or omit stages.

### `work_packages`

A managed package inside a stage, normally organized by discipline, location or commercial package.

Examples:

- Architectural Detailed Design
- MEP Coordination
- Authority NOCs
- Superstructure
- MEP Installation
- Facade
- Interior Fit-out

### `work_items`

The lowest shared execution/control unit. Generic `work_type` values can represent design activities, deliverables, authority submissions, client approvals, construction activities, inspections, material approvals, commissioning, administrative work, etc.

Each item can carry:

- responsible organization;
- status / priority / progress;
- planned/actual dates;
- budget line and work-item budget;
- blocked reason.

### `work_item_assignments`

Connects actual tenant users to work, with a responsibility label while preserving the user's fixed application access role.

Example:

- `Aisha Mathew` — Lead Architect — `REVIEWER` access — responsibility `LEAD_ARCHITECT`;
- `Layla Al Hashimi` — Projects Director — `MANAGER` access — responsibility `CLIENT_SPONSOR`;
- `Imran Shah` — HVAC Technician — `VIEWER` access — responsibility `TECHNICIAN`.

### Work links

V42 adds optional `work_item_id` links to:

- documents;
- timesheets;
- actual-cost entries;
- equipment usage;
- commitments;
- material receipts;
- variations.

This lets one work item become the drill-down point for evidence without replacing the specialist records.

---

## 6. Project Delivery API

Base path: `/api/v1/project-delivery`

Feature: `PROJECT_DELIVERY` (core Project Control feature)

### `GET /project-delivery/portfolio`

Returns only projects already visible to the caller through `ProjectService` authorization.

Response includes account name and project cards with:

- contract value;
- captured actual cost;
- overall work-item progress;
- open/blocked work;
- overdue documents;
- pending approvals;
- participant/stage counts.

### `GET /project-delivery/projects/{projectId}`

Uses existing project visibility authorization and returns:

- project header/KPIs;
- participating organizations;
- stages;
- work packages;
- work items;
- responsible organization;
- budget/actual/time metrics;
- assigned staff including profession + access role;
- connected document summaries.

This endpoint is a **read composition model**. It intentionally reads existing source-of-truth tables rather than copying financial/document state into a new projection table at this stage.

---

## 7. Frontend-to-backend mapping

| Frontend area | Primary backend API | Source of truth |
|---|---|---|
| Portfolio | `GET /project-delivery/portfolio` | projects + stages/work + docs + approvals + costs |
| Project drill-down | `GET /project-delivery/projects/{id}` | delivery hierarchy composed with existing records |
| Document Register | `/documents`, revision/issue APIs | documents + document_versions |
| Approval Inbox | document approval worklist/decision APIs | document approvals/steps |
| Workflows | document workflow APIs | document_control_workflows |
| Transmittals | transmittal APIs | document_transmittals/items/recipients |
| Budget & IPC | `/payment-applications`, commercial overview | payment applications + evidence documents |
| Project Controls | `/projects/{id}/controls/*` | contracts + budgets + forecasts |
| Resources & Cost | `/projects/{id}/resource-costs/*` | resources + rates + actual_cost_entries |
| Time Log | `/projects/{id}/time-log` | timesheets |
| Security | document/project capability endpoints | grants + project capability matrix |
| Notifications | workflow notification endpoints | notification outbox/delivery/in-app tables |
| WhatsApp Inbox | original CRM/WhatsApp APIs | conversations/messages |

Frontend authorization visibility is only a convenience. Backend authorization remains authoritative.

---

## 8. Demo account seeded by V42

Tenant display name becomes **Aurelia Developments PJSC** in the DEMO environment.

### Portfolio

- `AUR-CRK` — Aurelia Creek Residences — AED 420M
- `AUR-BDT` — Aurelia Business District Tower — AED 680M
- `AUR-MAR` — Aurelia Marina Hotel — AED 310M

### Companies

- Aurelia Developments PJSC — Client
- Meridian Engineering Consultants — Consultant
- GulfBuild Contracting LLC — Main Contractor
- Apex MEP Services LLC — Subcontractor
- Skyline Facades LLC — Subcontractor
- Prism Architects & Engineers — design consultant role on showcase project
- Vertex Interiors LLC — Subcontractor

### Staff examples

All seeded demo users use password `admin123`.

Useful logins:

- `enterprise.admin@aurelia.demo` — tenant/platform-style ADMIN, no organization
- `director@aurelia.demo` — Client Projects Director / MANAGER
- `finance@aurelia.demo` — Client Finance Manager / MANAGER
- `client.dc@aurelia.demo` — Client Document Controller / REVIEWER
- `design.manager@meridian.demo` — Consultant Design Manager / MANAGER
- `resident.engineer@meridian.demo` — Consultant Resident Engineer / REVIEWER
- `qs@meridian.demo` — Consultant Senior QS / MANAGER
- `mep.engineer@meridian.demo` — Consultant MEP Engineer / REVIEWER
- `pm@gulfbuild.demo` — Main Contractor Project Manager / MANAGER
- `site.engineer@gulfbuild.demo` — Main Contractor Site Engineer / REVIEWER
- `qaqc@gulfbuild.demo` — QA/QC Engineer / REVIEWER
- `foreman@gulfbuild.demo` — General Foreman / VIEWER
- `mep.manager@apex.demo` — MEP Subcontractor Manager / MANAGER
- `mep.supervisor@apex.demo` — HVAC Supervisor / REVIEWER
- `mep.worker@apex.demo` — HVAC Technician / VIEWER
- `architect@prism.demo` — Lead Architect / REVIEWER

The showcase project contains connected stage/package/work data, assignments, budgets, forecasts, resources, timesheets, actual costs, documents and live approvals.

---

## 9. Showcase drill-down story

Aurelia Creek Residences demonstrates why the hierarchy exists.

Example path:

`AUR-CRK -> Construction -> MEP Installation -> ME-301 Level 05 HVAC duct installation and inspection`

The work item is deliberately `BLOCKED` because `HVAC Inspection Request IR-234` was returned with comments requiring fire-damper access clearance correction. The same work item has:

- responsible subcontractor;
- MEP manager, supervisor, technician and consultant inspector assignments;
- logged hours;
- actual cost;
- budget amount;
- connected inspection document;
- return code / status;
- explicit blocked reason.

This is the expected product interaction: a user sees a project-level problem and can drill down until the evidence and responsible people are visible.

---

## 10. UI/product invariants

1. Normal users should start from **Projects / My Work / approvals**, not from a raw document table.
2. Document Register remains an expert control/register view.
3. Project Controls remains an expert commercial/forecast view.
4. Every summary metric should eventually be drillable to contributing stages/packages/work/items/records.
5. Project stages are configurable/template-driven.
6. Work-item types and approval routes are configuration/data, not UAE-specific Java branches.
7. AI may summarize, compare, identify risk and recommend attention. AI must not approve work, certify IPC or mark payment as paid.
8. Authorization is server-enforced; hidden frontend controls are never a security boundary.
9. Preserve audit evidence and source record IDs.
10. Do not duplicate existing project/document/payment/resource services when adding another view.

---

## 11. Remaining implementation priorities

The V42 slice establishes the project drill-down read model and demo data. The next implementation increments should be:

1. CRUD/configuration APIs for stage templates, work packages and work items.
2. Make document create/intake optionally select/resolve `workItemId`.
3. Make Time Log select `workItemId` first and document optionally.
4. Generalize approval orchestration so non-document records (timesheet, inspection, variation, leave, material acceptance) can use the same assignment/SLA concepts without pretending every approval is a document approval.
5. Project-aware WhatsApp routing: contact/project/work/document request resolution and safe confirmation when ambiguous.
6. `My Work` API aggregating assigned work items + approval steps + due documents for the acting user.
7. Role-scoped AI context packs at work-item, manager, organization and client/project levels.
8. Better cumulative IPC measurement/partial claiming where contracts require progressive valuation rather than one-document-one-live-claim semantics.
9. Authority/NOC register and configurable project templates.
10. End-to-end contract and authorization tests for Client / Consultant / Contractor / Subcontractor personas.

This document should be updated when those boundaries change so future AI/code sessions begin from the same product model.
