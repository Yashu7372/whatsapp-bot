-- Scope/workflow applicability is project contextual. These composite constraints
-- make the same-project invariant true in PostgreSQL/H2 as well as in services.
ALTER TABLE project_scopes
    ADD CONSTRAINT uk_project_scopes_project_id_id UNIQUE (project_id, id);

ALTER TABLE workflow_definitions
    ADD CONSTRAINT uk_workflow_definitions_project_id_id UNIQUE (project_id, id);

ALTER TABLE scope_workflow_bindings
    ADD CONSTRAINT fk_scope_workflow_bindings_project_scope
        FOREIGN KEY (project_id, scope_id)
        REFERENCES project_scopes(project_id, id);

ALTER TABLE scope_workflow_bindings
    ADD CONSTRAINT fk_scope_workflow_bindings_project_definition
        FOREIGN KEY (project_id, workflow_definition_id)
        REFERENCES workflow_definitions(project_id, id);

ALTER TABLE workflow_instances
    ADD CONSTRAINT fk_workflow_instances_project_scope
        FOREIGN KEY (project_id, scope_id)
        REFERENCES project_scopes(project_id, id);

ALTER TABLE workflow_instances
    ADD CONSTRAINT fk_workflow_instances_project_definition
        FOREIGN KEY (project_id, workflow_definition_id)
        REFERENCES workflow_definitions(project_id, id);
