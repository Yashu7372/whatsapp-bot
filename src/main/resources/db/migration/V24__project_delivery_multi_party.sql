-- =====================================================================
-- V24 - Multi-party project delivery
--
-- Construction work runs between separate companies: a client who funds it,
-- consultants who administer it, and contractors who build it. Until now a
-- document belonged to a tenant and nothing else, so there was no way to say
-- "this drawing came from the contractor and is awaiting the consultant".
--
-- The project is the sharing boundary. Organizations are the companies taking
-- part in it, users are attached to the organization they work for, and every
-- document carries the project it belongs to and the organization it came from.
-- Everything stays scoped by tenant_id, so tenant isolation is unchanged.
-- =====================================================================

-- ── Companies taking part in projects ────────────────────────────────
CREATE TABLE organizations (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name           VARCHAR(300)  NOT NULL,
    -- Short code used when numbering documents, e.g. the "ACME" in ACME-RFI-0042.
    org_code       VARCHAR(50)   NOT NULL,
    trade_license  VARCHAR(100),
    contact_email  VARCHAR(320),
    contact_phone  VARCHAR(50),
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_organizations_tenant_code UNIQUE (tenant_id, org_code)
);

CREATE INDEX idx_organizations_tenant ON organizations(tenant_id, active);

-- ── Projects ─────────────────────────────────────────────────────────
CREATE TABLE projects (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name              VARCHAR(300)   NOT NULL,
    project_code      VARCHAR(50)    NOT NULL,
    description       TEXT,
    contract_value    NUMERIC(18, 2),
    currency          VARCHAR(10)    NOT NULL DEFAULT 'AED',
    -- Percentage withheld from each certified payment until the defects period
    -- closes. Held on the project because it is a contract term, not a per-claim
    -- decision.
    retention_percent NUMERIC(5, 2)  NOT NULL DEFAULT 10.00,
    status            VARCHAR(50)    NOT NULL DEFAULT 'ACTIVE',
    start_date        DATE,
    end_date          DATE,
    created_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_projects_tenant_code UNIQUE (tenant_id, project_code),
    CONSTRAINT ck_projects_retention CHECK (retention_percent >= 0 AND retention_percent <= 100)
);

CREATE INDEX idx_projects_tenant ON projects(tenant_id, status);

-- ── Who is on a project, and in what capacity ────────────────────────
CREATE TABLE project_participants (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id            UUID         NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id       UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- CLIENT | CONSULTANT | CONTRACTOR | SUBCONTRACTOR
    party_role            VARCHAR(50)  NOT NULL,
    -- The inter-company link: a subcontractor sits under the contractor that
    -- engaged it, so a chain of responsibility can be walked in either direction.
    parent_participant_id UUID         REFERENCES project_participants(id) ON DELETE SET NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW(),
    -- One company may hold more than one capacity on a project (a consultant can
    -- also be the supervising engineer), so the role forms part of the key.
    CONSTRAINT uk_participant_project_org_role UNIQUE (project_id, organization_id, party_role)
);

CREATE INDEX idx_participants_project ON project_participants(project_id, active);
CREATE INDEX idx_participants_org     ON project_participants(organization_id);
CREATE INDEX idx_participants_parent  ON project_participants(parent_participant_id);

-- ── Which company a user works for ───────────────────────────────────
ALTER TABLE tenant_users ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL;
CREATE INDEX idx_tenant_users_org ON tenant_users(organization_id);

-- ── Documents become project instruments ─────────────────────────────
ALTER TABLE documents ADD COLUMN project_id        UUID REFERENCES projects(id) ON DELETE SET NULL;
ALTER TABLE documents ADD COLUMN originator_org_id UUID REFERENCES organizations(id) ON DELETE SET NULL;
-- Human-facing reference such as ACME-RFI-0042; unique per project.
ALTER TABLE documents ADD COLUMN document_code     VARCHAR(80);
-- Contractual response deadline for the party currently holding the document.
ALTER TABLE documents ADD COLUMN due_at            TIMESTAMP;
-- CODE_A | CODE_B | CODE_C | CODE_D — the reviewer's return status. Distinct
-- from status: a document can be returned CODE_C (revise and resubmit) which is
-- neither a plain approval nor an outright rejection.
ALTER TABLE documents ADD COLUMN review_outcome    VARCHAR(20);

CREATE INDEX idx_documents_project ON documents(project_id, status);
CREATE INDEX idx_documents_due     ON documents(due_at) WHERE due_at IS NOT NULL;
CREATE UNIQUE INDEX uk_documents_project_code
    ON documents(project_id, document_code)
    WHERE project_id IS NOT NULL AND document_code IS NOT NULL;

-- ── Per-project, per-type reference numbering ────────────────────────
-- Each instrument runs its own series so RFIs and payment certificates number
-- independently. Which types exist is data, not code, so a project can define
-- whatever its contract calls for.
CREATE TABLE document_number_series (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id   UUID         NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_type     VARCHAR(100) NOT NULL,
    prefix       VARCHAR(30)  NOT NULL,
    next_number  INTEGER      NOT NULL DEFAULT 1,
    padding      INTEGER      NOT NULL DEFAULT 4,
    -- Contractual reply window for this instrument, used to set documents.due_at.
    response_days INTEGER,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_number_series_project_type UNIQUE (project_id, doc_type),
    CONSTRAINT ck_number_series_next CHECK (next_number >= 1),
    CONSTRAINT ck_number_series_padding CHECK (padding BETWEEN 1 AND 10)
);

-- ── Payment applications ─────────────────────────────────────────────
-- The money side of the flow. An application is claimed by one organization and
-- certified by another, and its value is built from the documents beneath it
-- rather than typed in free-hand.
CREATE TABLE payment_applications (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id            UUID           NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    application_ref       VARCHAR(80)    NOT NULL,
    claimed_by_org_id     UUID           NOT NULL REFERENCES organizations(id),
    period_start          DATE,
    period_end            DATE,
    -- Total of the linked document items.
    gross_claimed         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    -- Cumulative value certified on earlier applications, so this one can be
    -- expressed as the movement rather than a restatement.
    previously_certified  NUMERIC(18, 2) NOT NULL DEFAULT 0,
    retention_percent     NUMERIC(5, 2)  NOT NULL DEFAULT 0,
    retention_amount      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    net_certified         NUMERIC(18, 2) NOT NULL DEFAULT 0,
    currency              VARCHAR(10)    NOT NULL DEFAULT 'AED',
    -- DRAFT | SUBMITTED | CERTIFIED | REJECTED | PAID
    status                VARCHAR(50)    NOT NULL DEFAULT 'DRAFT',
    submitted_at          TIMESTAMP,
    certified_by          UUID           REFERENCES tenant_users(id) ON DELETE SET NULL,
    certified_at          TIMESTAMP,
    created_by            UUID           REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_payment_app_project_ref UNIQUE (project_id, application_ref),
    CONSTRAINT ck_payment_app_retention CHECK (retention_percent >= 0 AND retention_percent <= 100)
);

CREATE INDEX idx_payment_apps_project ON payment_applications(project_id, status);
CREATE INDEX idx_payment_apps_org     ON payment_applications(claimed_by_org_id);

-- Each line ties money to the document that evidences the work.
CREATE TABLE payment_application_items (
    id                     UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payment_application_id UUID           NOT NULL REFERENCES payment_applications(id) ON DELETE CASCADE,
    document_id            UUID           REFERENCES documents(id) ON DELETE SET NULL,
    description            VARCHAR(500),
    amount                 NUMERIC(18, 2) NOT NULL DEFAULT 0,
    created_at             TIMESTAMP      NOT NULL DEFAULT NOW(),
    -- A document may only be claimed once within an application.
    CONSTRAINT uk_payment_item_app_document UNIQUE (payment_application_id, document_id)
);

CREATE INDEX idx_payment_items_app ON payment_application_items(payment_application_id);
CREATE INDEX idx_payment_items_doc ON payment_application_items(document_id);
