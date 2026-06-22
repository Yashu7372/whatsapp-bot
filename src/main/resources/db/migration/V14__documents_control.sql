-- V14: documents + document control workflow

-- Master document registry
CREATE TABLE documents (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title         VARCHAR(500)  NOT NULL,
    doc_type      VARCHAR(100)  NOT NULL DEFAULT 'GENERAL',
    description   TEXT,
    tags          TEXT[],
    current_version INT         NOT NULL DEFAULT 1,
    status        VARCHAR(50)   NOT NULL DEFAULT 'DRAFT',
    workflow_id   UUID,
    created_by    UUID          REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);

-- Versioned content per document
CREATE TABLE document_versions (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID          NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    version_num   INT           NOT NULL,
    asset_id      UUID          REFERENCES media_assets(id) ON DELETE SET NULL,
    change_notes  TEXT,
    created_by    UUID          REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_doc_version UNIQUE (document_id, version_num)
);

-- Custom workflow templates per document type
CREATE TABLE document_control_workflows (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          VARCHAR(200)  NOT NULL,
    doc_type      VARCHAR(100)  NOT NULL,
    steps         JSONB         NOT NULL DEFAULT '[]',
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_workflow_tenant_type UNIQUE (tenant_id, doc_type)
);

-- Per-document approval instances
CREATE TABLE document_approvals (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    UUID         NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tenant_id      UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    workflow_id    UUID         REFERENCES document_control_workflows(id) ON DELETE SET NULL,
    current_step   INT          NOT NULL DEFAULT 0,
    status         VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    started_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMP,
    initiated_by   UUID         REFERENCES tenant_users(id) ON DELETE SET NULL
);

-- Individual step decisions
CREATE TABLE document_approval_steps (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id    UUID         NOT NULL REFERENCES document_approvals(id) ON DELETE CASCADE,
    step_index     INT          NOT NULL,
    step_name      VARCHAR(200),
    reviewer_id    UUID         REFERENCES tenant_users(id) ON DELETE SET NULL,
    reviewer_email VARCHAR(320),
    decision       VARCHAR(50),
    comments       TEXT,
    decided_at     TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Comments on documents
CREATE TABLE document_comments (
    id          UUID      PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID      NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    tenant_id   UUID      NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    author_id   UUID      REFERENCES tenant_users(id) ON DELETE SET NULL,
    body        TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_tenant        ON documents(tenant_id);
CREATE INDEX idx_documents_status        ON documents(status);
CREATE INDEX idx_doc_versions_document   ON document_versions(document_id);
CREATE INDEX idx_doc_approvals_document  ON document_approvals(document_id);
CREATE INDEX idx_doc_comments_document   ON document_comments(document_id);
CREATE INDEX idx_dcw_tenant_type         ON document_control_workflows(tenant_id, doc_type);

-- Add workflow FK now that workflow table exists
ALTER TABLE documents ADD CONSTRAINT fk_doc_workflow
    FOREIGN KEY (workflow_id) REFERENCES document_control_workflows(id) ON DELETE SET NULL;
