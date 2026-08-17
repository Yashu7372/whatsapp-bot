# Enterprise Project Control E2E Scenario

This scenario validates the normal application foundation (not AI) using the `DEMO` tenant.

## Existing portfolio foundation (V42/V43)

The demo tenant represents **Aurelia Developments PJSC** and contains three projects. The primary E2E project is:

- `AUR-CRK` — Aurelia Creek Residences
- Client — Aurelia Developments PJSC
- Consultant — Meridian Engineering Consultants
- Main contractor — GulfBuild Contracting LLC
- Specialist subcontractors — Apex MEP Services, Skyline Facades, Prism Architects & Engineers, Vertex Interiors

V42 also seeds realistic business users (project director, finance manager, document controllers, design manager, resident engineer, QS, MEP engineer, project manager, site engineer, QA/QC, foreman, commercial manager, supervisors and specialists), project stages, packages, work items and documents. V43 links the commercial side to that delivery hierarchy.

## Additional operational fixture (V45)

V45 adds one dedicated contractor document controller:

- `document.controller@gulfbuild.demo`
- Company: GulfBuild Contracting LLC
- Access role: REVIEWER
- Job title: Document Controller
- Department: Project Controls

Persisted WhatsApp delivery is deliberately disabled and no phone number is stored. Automated testing temporarily uses a synthetic E.164 number while the Meta client is mocked.

V45 also adds two tenant-level workflow templates.

### Shop drawing workflow

1. Contractor Document Control Check — named contractor document controller — INTERNAL_REVIEW — 8h SLA
2. Consultant Technical Review — consultant party role — TECHNICAL_REVIEW — 48h SLA
3. Client Approval — client party role — CLIENT_APPROVAL — 48h SLA

### Material submittal workflow

1. Contractor Document Control Check — named contractor document controller — INTERNAL_REVIEW — 8h SLA
2. Consultant Material Review — consultant party role — TECHNICAL_REVIEW — 72h SLA
3. Client Approval — client party role — CLIENT_APPROVAL — 48h SLA

## Automated E2E test

`EnterpriseProjectControlE2ETest` runs against the real PostgreSQL instance provided by Backend CI.

Journey:

1. Create a `SHOP_DRAWING` against `AUR-CRK`.
2. Confirm the project document-number series generated a reference.
3. Confirm the shop-drawing workflow was attached automatically by document type.
4. Submit the document for approval.
5. Confirm three approval steps were materialised.
6. Confirm the first step is assigned to the contractor document controller.
7. Confirm the database trigger created an `APPROVAL_ASSIGNED` outbox event.
8. Run the production audience dispatcher.
9. Confirm an unread in-app notification exists for the contractor document controller.
10. Enable WhatsApp for a synthetic test-only destination and run the production delivery worker.
11. Confirm `WhatsAppGraphClient` is called; the Graph boundary is mocked so no real message leaves CI.
12. Contractor document controller approves the internal gate.
13. Consultant manager approves the technical-review stage.
14. Client project director approves the client stage.
15. Confirm both approval and document are `APPROVED`.
16. Confirm the document audit chain contains the create, submit and approval decisions.

This covers the real workflow/data/notification path while isolating only the external Meta transport.

## What is and is not tested for WhatsApp

The application already has a Meta Graph adapter. CI validates the integration boundary without sending to a real number. A real outbound test requires environment-specific Meta credentials, a registered/test recipient and, for proactive messages outside Meta's customer-service window, an approved WhatsApp template.

## App notification behavior

In-app notifications are durable database records and are always retained regardless of external-channel preferences. The current enterprise Notification Center reads these records and supports unread counts, mark-read/mark-all-read, WhatsApp/email preferences and tenant-admin delivery audit.

This is an **in-app notification inbox**, not an OS-level mobile push service. Native/background push would require a separate device-token + FCM/APNs (or Web Push/VAPID) channel and should not be confused with the existing durable in-app notification path.
