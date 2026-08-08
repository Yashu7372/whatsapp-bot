-- =====================================================================
-- V30 - Document lifecycle, transmittals and multi-company authorization scope
-- =====================================================================

-- A project can contain a client budget plus independent consultant/contractor/subcontractor
-- commercial positions. organization_id NULL is the project/client control budget; a non-null
-- organization_id is a private organization budget under the same project id.
ALTER TABLE budget_versions
    ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;

ALTER TABLE budget_versions DROP CONSTRAINT uk_budget_project_version;
CREATE UNIQUE INDEX uk_budget_project_scope_version
    ON budget_versions(project_id, COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), version_no);
CREATE INDEX idx_budget_versions_org ON budget_versions(project_id, organization_id, status);

-- Explicit security and issue metadata. PROJECT means all active project participants may read;
-- ORGANIZATION means originator org + explicitly granted parties; RESTRICTED means grants or
-- workflow assignment only (tenant platform administrators still retain audited break-glass access).
ALTER TABLE documents ADD COLUMN security_classification VARCHAR(30) NOT NULL DEFAULT 'PROJECT';
ALTER TABLE documents ADD COLUMN discipline VARCHAR(80);
ALTER TABLE documents ADD COLUMN package_code VARCHAR(80);
ALTER TABLE documents ADD COLUMN location_code VARCHAR(80);
ALTER TABLE documents ADD COLUMN issue_purpose VARCHAR(40);
ALTER TABLE documents ADD COLUMN current_revision_code VARCHAR(30) NOT NULL DEFAULT '01';
ALTER TABLE documents ADD COLUMN issued_at TIMESTAMP;
ALTER TABLE documents ADD COLUMN issued_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL;
ALTER TABLE documents ADD CONSTRAINT ck_document_security
    CHECK (security_classification IN ('PROJECT','ORGANIZATION','RESTRICTED'));
ALTER TABLE documents ADD CONSTRAINT ck_document_issue_purpose
    CHECK (issue_purpose IS NULL OR issue_purpose IN ('FOR_REVIEW','FOR_APPROVAL','FOR_INFORMATION','FOR_CONSTRUCTION','AS_BUILT'));

ALTER TABLE document_versions ADD COLUMN revision_code VARCHAR(30);
ALTER TABLE document_versions ADD COLUMN issue_status VARCHAR(30) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE document_versions ADD COLUMN issue_purpose VARCHAR(40);
ALTER TABLE document_versions ADD COLUMN issued_at TIMESTAMP;
ALTER TABLE document_versions ADD COLUMN issued_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL;
ALTER TABLE document_versions ADD CONSTRAINT ck_document_version_issue_status
    CHECK (issue_status IN ('DRAFT','ISSUED','SUPERSEDED'));
UPDATE document_versions SET revision_code = LPAD(version_num::text, 2, '0') WHERE revision_code IS NULL;
ALTER TABLE document_versions ALTER COLUMN revision_code SET NOT NULL;
CREATE UNIQUE INDEX uk_document_revision_code ON document_versions(document_id, revision_code);

-- Existing document grants become useful at organization level as well as user/role level.
ALTER TABLE document_access_grants
    ADD COLUMN organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE;
ALTER TABLE document_access_grants ADD CONSTRAINT ck_document_grant_principal
    CHECK (user_id IS NOT NULL OR role_code IS NOT NULL OR organization_id IS NOT NULL);
CREATE INDEX idx_document_grants_org ON document_access_grants(document_id, organization_id, permission_code);

CREATE TABLE document_transmittals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    transmittal_no VARCHAR(100) NOT NULL,
    sender_organization_id UUID NOT NULL REFERENCES organizations(id),
    purpose VARCHAR(40) NOT NULL,
    subject VARCHAR(300),
    message TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    issued_at TIMESTAMP,
    issued_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_transmittal_project_no UNIQUE(project_id, transmittal_no),
    CONSTRAINT ck_transmittal_status CHECK(status IN ('DRAFT','ISSUED','PARTIALLY_ACKNOWLEDGED','ACKNOWLEDGED','CLOSED')),
    CONSTRAINT ck_transmittal_purpose CHECK(purpose IN ('FOR_REVIEW','FOR_APPROVAL','FOR_INFORMATION','FOR_CONSTRUCTION','AS_BUILT'))
);
CREATE INDEX idx_transmittals_project_sender ON document_transmittals(project_id, sender_organization_id, created_at DESC);

CREATE TABLE document_transmittal_recipients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    transmittal_id UUID NOT NULL REFERENCES document_transmittals(id) ON DELETE CASCADE,
    recipient_organization_id UUID NOT NULL REFERENCES organizations(id),
    acknowledged_at TIMESTAMP,
    acknowledged_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    UNIQUE(transmittal_id, recipient_organization_id)
);
CREATE INDEX idx_transmittal_recipients_org ON document_transmittal_recipients(recipient_organization_id, acknowledged_at);

-- Transmittals preserve the exact issued revision forever, not merely the mutable document id.
CREATE TABLE document_transmittal_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    transmittal_id UUID NOT NULL REFERENCES document_transmittals(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id),
    document_version_id UUID NOT NULL REFERENCES document_versions(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(transmittal_id, document_version_id)
);
CREATE INDEX idx_transmittal_items_document ON document_transmittal_items(document_id, document_version_id);
