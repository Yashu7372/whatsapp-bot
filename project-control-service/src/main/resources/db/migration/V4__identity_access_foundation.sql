CREATE TABLE user_accounts (
    id UUID PRIMARY KEY,
    external_subject VARCHAR(160) NOT NULL,
    email VARCHAR(240),
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_accounts_subject UNIQUE (external_subject)
);

CREATE TABLE workspace_memberships (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    user_id UUID NOT NULL,
    access_role VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    valid_from DATE,
    valid_to DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workspace_memberships_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT fk_workspace_memberships_user FOREIGN KEY (user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_workspace_memberships_user UNIQUE (workspace_id, user_id),
    CONSTRAINT ck_workspace_memberships_dates CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE TABLE organization_memberships (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    responsibility_code VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    valid_from DATE,
    valid_to DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_organization_memberships_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_organization_memberships_user FOREIGN KEY (user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_organization_memberships_user UNIQUE (organization_id, user_id),
    CONSTRAINT ck_organization_memberships_dates CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE TABLE scope_assignments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    user_id UUID NOT NULL,
    project_participant_id UUID NOT NULL,
    responsibility_code VARCHAR(100) NOT NULL,
    access_level VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    valid_from DATE,
    valid_to DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_scope_assignments_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_scope_assignments_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_scope_assignments_user FOREIGN KEY (user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_scope_assignments_participant FOREIGN KEY (project_participant_id) REFERENCES project_participants(id),
    CONSTRAINT uk_scope_assignments_context UNIQUE (scope_id, user_id, project_participant_id, responsibility_code),
    CONSTRAINT ck_scope_assignments_dates CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE INDEX idx_workspace_memberships_user ON workspace_memberships (user_id, status);
CREATE INDEX idx_organization_memberships_user ON organization_memberships (user_id, status);
CREATE INDEX idx_scope_assignments_user_project ON scope_assignments (user_id, project_id, status);
CREATE INDEX idx_scope_assignments_scope ON scope_assignments (scope_id, status);
