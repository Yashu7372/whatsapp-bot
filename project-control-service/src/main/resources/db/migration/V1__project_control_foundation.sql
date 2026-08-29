CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_workspaces_code UNIQUE (code)
);

CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    legal_name VARCHAR(240) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(240) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    start_date DATE,
    end_date DATE,
    currency VARCHAR(3) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_projects_workspace FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
    CONSTRAINT uk_projects_workspace_code UNIQUE (workspace_id, code),
    CONSTRAINT ck_projects_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TABLE project_participants (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    party_role VARCHAR(80) NOT NULL,
    parent_participant_id UUID,
    status VARCHAR(32) NOT NULL,
    valid_from DATE,
    valid_to DATE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_project_participants_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_participants_org FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_project_participants_parent FOREIGN KEY (parent_participant_id) REFERENCES project_participants(id),
    CONSTRAINT ck_project_participants_dates CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE TABLE project_scopes (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    parent_scope_id UUID,
    scope_type VARCHAR(80) NOT NULL,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(240) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    start_date DATE,
    end_date DATE,
    configuration_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_project_scopes_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_project_scopes_parent FOREIGN KEY (parent_scope_id) REFERENCES project_scopes(id),
    CONSTRAINT uk_project_scopes_project_code UNIQUE (project_id, code),
    CONSTRAINT ck_project_scopes_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date)
);

CREATE TABLE scope_participants (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    project_participant_id UUID NOT NULL,
    responsibility VARCHAR(240),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_scope_participants_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_scope_participants_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_scope_participants_participant FOREIGN KEY (project_participant_id) REFERENCES project_participants(id),
    CONSTRAINT uk_scope_participants_assignment UNIQUE (scope_id, project_participant_id)
);

CREATE TABLE scope_capabilities (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    capability_code VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL,
    configuration_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_scope_capabilities_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_scope_capabilities_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT uk_scope_capabilities_scope_code UNIQUE (scope_id, capability_code)
);

CREATE INDEX idx_project_participants_org ON project_participants (organization_id);
CREATE INDEX idx_project_participants_project ON project_participants (project_id);
CREATE INDEX idx_project_scopes_project_parent ON project_scopes (project_id, parent_scope_id);
CREATE INDEX idx_scope_participants_participant ON scope_participants (project_participant_id);
CREATE INDEX idx_scope_capabilities_scope_enabled ON scope_capabilities (scope_id, enabled);
