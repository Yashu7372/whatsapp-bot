CREATE TABLE workflow_definitions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    code VARCHAR(100) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(240) NOT NULL,
    purpose_code VARCHAR(100) NOT NULL,
    required_capability_code VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_definitions_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT uk_workflow_definitions_project_code_version UNIQUE (project_id, code, version),
    CONSTRAINT ck_workflow_definitions_version CHECK (version >= 1)
);

CREATE TABLE workflow_step_definitions (
    id UUID PRIMARY KEY,
    workflow_definition_id UUID NOT NULL,
    step_sequence INTEGER NOT NULL,
    step_code VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    completion_action_code VARCHAR(100) NOT NULL,
    assignment_json TEXT NOT NULL,
    configuration_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_step_definitions_definition
        FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definitions(id),
    CONSTRAINT uk_workflow_step_definitions_sequence
        UNIQUE (workflow_definition_id, step_sequence),
    CONSTRAINT uk_workflow_step_definitions_code
        UNIQUE (workflow_definition_id, step_code),
    CONSTRAINT ck_workflow_step_definitions_sequence CHECK (step_sequence >= 1)
);

CREATE TABLE scope_workflow_bindings (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    workflow_definition_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL,
    configuration_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_scope_workflow_bindings_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_scope_workflow_bindings_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_scope_workflow_bindings_definition
        FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definitions(id),
    CONSTRAINT uk_scope_workflow_bindings UNIQUE (scope_id, workflow_definition_id)
);

CREATE TABLE workflow_instances (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    workflow_definition_id UUID NOT NULL,
    business_key VARCHAR(160) NOT NULL,
    title VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step_instance_id UUID,
    current_step_sequence INTEGER,
    current_step_code VARCHAR(100),
    initiated_by_reference VARCHAR(200),
    initiated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    context_json TEXT NOT NULL,
    CONSTRAINT fk_workflow_instances_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_workflow_instances_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_workflow_instances_definition
        FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definitions(id),
    CONSTRAINT uk_workflow_instances_business_key
        UNIQUE (project_id, workflow_definition_id, business_key)
);

CREATE TABLE workflow_step_instances (
    id UUID PRIMARY KEY,
    workflow_instance_id UUID NOT NULL,
    step_definition_id UUID NOT NULL,
    step_sequence INTEGER NOT NULL,
    step_code VARCHAR(100) NOT NULL,
    step_name VARCHAR(240) NOT NULL,
    visit_number INTEGER NOT NULL,
    assignment_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    activated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_workflow_step_instances_instance
        FOREIGN KEY (workflow_instance_id) REFERENCES workflow_instances(id),
    CONSTRAINT fk_workflow_step_instances_definition
        FOREIGN KEY (step_definition_id) REFERENCES workflow_step_definitions(id),
    CONSTRAINT uk_workflow_step_instances_visit
        UNIQUE (workflow_instance_id, step_sequence, visit_number),
    CONSTRAINT ck_workflow_step_instances_sequence CHECK (step_sequence >= 1),
    CONSTRAINT ck_workflow_step_instances_visit CHECK (visit_number >= 1)
);

ALTER TABLE workflow_instances
    ADD CONSTRAINT fk_workflow_instances_current_step
    FOREIGN KEY (current_step_instance_id) REFERENCES workflow_step_instances(id);

CREATE TABLE workflow_actions (
    id UUID PRIMARY KEY,
    workflow_instance_id UUID NOT NULL,
    step_instance_id UUID,
    action_type VARCHAR(32) NOT NULL,
    action_code VARCHAR(100) NOT NULL,
    actor_reference VARCHAR(200),
    from_step_code VARCHAR(100),
    to_step_code VARCHAR(100),
    comment TEXT,
    metadata_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_workflow_actions_instance
        FOREIGN KEY (workflow_instance_id) REFERENCES workflow_instances(id),
    CONSTRAINT fk_workflow_actions_step
        FOREIGN KEY (step_instance_id) REFERENCES workflow_step_instances(id)
);

CREATE INDEX idx_workflow_definitions_project ON workflow_definitions (project_id, code, version);
CREATE INDEX idx_workflow_step_definitions_definition ON workflow_step_definitions (workflow_definition_id, step_sequence);
CREATE INDEX idx_scope_workflow_bindings_scope_enabled ON scope_workflow_bindings (scope_id, enabled);
CREATE INDEX idx_workflow_instances_scope_status ON workflow_instances (scope_id, status);
CREATE INDEX idx_workflow_step_instances_instance ON workflow_step_instances (workflow_instance_id, activated_at);
CREATE INDEX idx_workflow_actions_instance ON workflow_actions (workflow_instance_id, created_at);
