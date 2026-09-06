# Project Control — Aurelia CHW Demo

This is the canonical local demo for the fresh `project-control-service` Spring Modulith application.

It demonstrates one connected business story rather than a tour of unrelated APIs:

```text
Aurelia Creek Residences
  -> Construction / Zone B / CHW Installation
  -> Controlled Shop Drawing Rev 03
  -> Site -> QCE -> QC/DC -> Consultant Inspector -> Consultant RE
  -> role-aware reviewer brief / bounded AI reasoning
  -> 320 m submitted for verification
  -> 300 m accepted + 20 m rework
  -> 20 m corrected and accepted in a child verification package
  -> 320 m accepted in two immutable measurements
  -> 320 m x AED 400/m = AED 128,000 valuation
  -> IPC-005 claims AED 128,000
  -> client certifies AED 124,000
  -> PAY-005 pays AED 124,000
  -> reverse trace from payment to accepted measurement, verification, evidence and workflow
```

The demo intentionally proves the frozen Project Control separation:

- document/extractor evidence is not accepted physical quantity;
- AI reasoning is not workflow authority;
- workflow authority is contextual and deterministic;
- accepted measurement is not a manually entered valuation amount;
- certified value is distinct from claimed value;
- payment remains reverse-traceable to the evidence and decisions that justify it.

## 1. Run locally

From `project-control-service`:

```bash
mvn -Dspring-boot.run.profiles=local spring-boot:run
```

The local profile uses persistent H2 and enables the canonical demo fixture by default.

To disable the fixture:

```text
PROJECT_CONTROL_DEMO_ENABLED=false
```

The first clean startup creates the demo business data through the normal Project Control application services. It does not insert a parallel demo domain model or bypass authorization/workflow logic.

## 2. Demo accounts

All accounts use:

```text
Project123!
```

| Actor | Login | Demo responsibility |
|---|---|---|
| Project Admin | `admin@local.demo` | project/workflow configuration and trusted extractor evidence registration |
| Aisha Khan | `site@local.demo` | Site Team |
| Ravi Menon | `qce@local.demo` | QCE |
| Sara Ali | `qcdc@local.demo` | QC/DC |
| Daniel Lee | `inspector@local.demo` | Consultant Inspector / field verifier |
| Omar Rahman | `re@local.demo` | Consultant RE |
| Maya Joseph | `viewer@local.demo` | scope-only viewer |
| Farah Nasser | `contractor.qs@local.demo` | Contractor QS / valuation and IPC preparation |
| Lina Haddad | `client.qs@local.demo` | Client QS / certification and payment |

## 3. Import the Postman collection

Import:

```text
postman/Project-Control-CHW-Demo.postman_collection.json
```

Default collection variables:

```text
baseUrl  = http://localhost:8080
password = Project123!
```

Run requests in folder order.

Postman must keep cookies enabled because the new Project Control service uses a real Spring Security server-side session. The collection retrieves the CSRF token and refreshes it after each user switch.

## 4. Demo sequence

### Folder 00 — Setup & Manifest

1. Obtain CSRF token.
2. Login as Project Admin.
3. Refresh CSRF for the authenticated session.
4. Load `/api/local/demo-scenario`.

The local-only manifest resolves generated database UUIDs from stable business codes so the collection does not hardcode random IDs.

Expected starting state:

```text
Project: AUR-CRK / Aurelia Creek Residences
Scope:   CHW-ZB-005 / Chilled-Water Piping - Zone B
Document: AUR-CRK-MEP-SD-004 Rev 03
Document workflow: RUNNING at SITE_TEAM
Verification package: WVP-CHW-001 DRAFT, 320 m claimed
Contract: AUR-GB-CHW-001
Contract item: CHW-QTY, 320 m @ AED 400/m
```

### Folder 01 — Controlled Drawing + AI-Assisted Review

Run the real responsibility chain:

```text
Site Team --SUBMIT--> QCE --VERIFY--> QC/DC --RECEIVE-->
Consultant Inspector --REVIEW--> Consultant RE --APPROVE--> COMPLETED
```

Do not skip the negative proof request: the Site Team deliberately attempts the QCE action and receives `403`.

At QCE, show the reviewer brief before clicking Verify. The brief contains only authorized project/scope/document/workflow/evidence context.

If AI is disabled, `reasoningMode` is `EVIDENCE_ONLY` and the demo still works. If AI is enabled, the exact same bounded context is sent to the reasoning worker and `reasoningMode` becomes `AI_ASSISTED`.

### Folder 02 — Verification, Partial Acceptance & Rework

The contractor submits the already-prepared `WVP-CHW-001` for 320 m.

The Consultant Inspector performs the terminal verification workflow action, then records:

```text
Submitted: 320 m
Accepted:  300 m
Rework:     20 m
```

The first attempt is closed as `PARTIALLY_ACCEPTED`; it is not overwritten.

A child package `WVP-CHW-002` explicitly references the first attempt, offers the remaining 20 m after rework, and is accepted.

The result is two immutable measurements:

```text
Measurement 1: 300 m accepted / 20 m rejected-rework
Measurement 2:  20 m accepted /  0 m rejected
----------------------------------------------
Accepted truth: 320 m
```

### Folder 03 — Valuation, IPC, Certification, Payment & Trace

First run the negative valuation request. It deliberately tries to submit a manual AED value for a `QUANTITY_RATE` item and must receive `400`.

Then value only accepted measurements:

```text
300 m x AED 400/m = AED 120,000
 20 m x AED 400/m = AED   8,000
--------------------------------
Valued to date     = AED 128,000
```

The Contractor QS creates and submits `IPC-005` for AED 128,000.

The Client QS certifies:

```text
First valuation certified:  AED 116,000
Second valuation certified: AED   8,000
----------------------------------------
Certified total:             AED 124,000
```

Then record:

```text
PAY-005 = AED 124,000
```

Finish on:

```text
GET /api/v1/projects/{projectId}/payments/{paymentId}/trace
```

Each quantity-backed payment line must return:

```text
verificationMappingStatus = ACCEPTED_MEASUREMENT_TYPED_TRACE_COMPLETE
```

The reverse trace connects:

```text
Payment
 -> IPC / Payment Application
 -> IPC Line
 -> Valuation
 -> Accepted Measurement
 -> Verification Decision
 -> Verification Package
 -> Controlled Document Revision / Evidence
 -> Generic Workflow
 -> Actor / Organization
 -> Project Scope
```

That reverse trace is the recommended final screen of the demo.

## 5. Optional AI-assisted mode

The demo works without an AI provider.

To enable the bounded OpenAI-compatible reasoning worker, set for example:

```text
PROJECT_CONTROL_AI_ENABLED=true
PROJECT_CONTROL_AI_API_KEY=<your key>
PROJECT_CONTROL_AI_MODEL=<configured model>
```

The model receives only the authorized reviewer context assembled by Project Control. It has no tools, database access or workflow commands and cannot approve, verify, certify, value or pay.

## 6. Reset for another live demo

Stop the service and delete the local demo state from the `project-control-service` working directory:

```text
project-control-local.mv.db
project-control-local.trace.db   (if present)
project-control-files/           (demo PDFs)
```

Then restart with the `local` profile. Flyway recreates the schema, local credential users are created first, and the canonical business fixture is seeded again.

## 7. Recommended presentation message

> Evidence establishes facts. Workflow establishes authority. Measurement establishes accepted work. Commercial rules establish value. AI helps the worker understand the context. Project Control remains the source of truth.
