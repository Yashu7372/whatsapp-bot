-- Parallel workflow execution support + project capability administration.

-- Recipient creation time lets the transmittal detail screen reconstruct a contractual history
-- from authoritative records without maintaining a second mutable timeline table.
ALTER TABLE document_transmittal_recipients
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT NOW();

-- Project-specific override of the platform default capability matrix.
-- Absence of a row means use the safe code default. Explicit DENY always wins over default allow.
CREATE TABLE project_capability_overrides (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    party_role VARCHAR(40) NOT NULL,
    user_role VARCHAR(40) NOT NULL,
    permission_code VARCHAR(100) NOT NULL,
    effect VARCHAR(10) NOT NULL,
    data_scope VARCHAR(20),
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    updated_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_capability_party_role CHECK (party_role IN ('CLIENT','CONSULTANT','CONTRACTOR','SUBCONTRACTOR')),
    CONSTRAINT ck_capability_user_role CHECK (user_role IN ('ADMIN','MANAGER','REVIEWER','VIEWER')),
    CONSTRAINT ck_capability_effect CHECK (effect IN ('ALLOW','DENY')),
    CONSTRAINT ck_capability_scope CHECK (data_scope IS NULL OR data_scope IN ('PROJECT','ORGANIZATION','ASSIGNED')),
    CONSTRAINT uk_project_capability_override UNIQUE(project_id,party_role,user_role,permission_code)
);
CREATE INDEX idx_project_capability_project ON project_capability_overrides(tenant_id,project_id);

-- Unified immutable audit for permission administration. Document-specific audit remains in the
-- document hash chain; these rows record project capability and explicit grant/classification changes.
CREATE TABLE permission_audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    document_id UUID REFERENCES documents(id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    event_type VARCHAR(80) NOT NULL,
    principal_type VARCHAR(40),
    principal_value VARCHAR(320),
    permission_code VARCHAR(100),
    old_value JSONB,
    new_value JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_permission_audit_project ON permission_audit_events(tenant_id,project_id,created_at DESC);
CREATE INDEX idx_permission_audit_document ON permission_audit_events(tenant_id,document_id,created_at DESC);

-- Parallel groups must be queryable as the active stage. The current_step column continues to
-- point at the first index of the active group; all undecided rows in that group are concurrently active.
CREATE INDEX idx_approval_parallel_group
    ON document_approval_steps(approval_id,parallel_group,step_index)
    WHERE decision IS NULL;
