CREATE TABLE verification_packages (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    package_number VARCHAR(160) NOT NULL,
    subject_type VARCHAR(100) NOT NULL,
    submitting_organization_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    submitted_by_user_id UUID,
    status VARCHAR(40) NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    parent_package_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_verification_packages_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_verification_packages_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_verification_packages_org FOREIGN KEY (submitting_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_verification_packages_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_verification_packages_submitted_by FOREIGN KEY (submitted_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_verification_packages_parent FOREIGN KEY (parent_package_id) REFERENCES verification_packages(id),
    CONSTRAINT uk_verification_packages_project_number UNIQUE (project_id, package_number)
);

CREATE TABLE verification_items (
    id UUID PRIMARY KEY,
    verification_package_id UUID NOT NULL,
    subject_resource_reference VARCHAR(500) NOT NULL,
    claimed_progress DECIMAL(7,4),
    claimed_quantity DECIMAL(19,4),
    unit VARCHAR(40),
    completion_statement VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_verification_items_package FOREIGN KEY (verification_package_id) REFERENCES verification_packages(id),
    CONSTRAINT ck_verification_items_progress CHECK (claimed_progress IS NULL OR (claimed_progress >= 0 AND claimed_progress <= 100)),
    CONSTRAINT ck_verification_items_quantity CHECK (claimed_quantity IS NULL OR claimed_quantity >= 0),
    CONSTRAINT ck_verification_items_quantity_unit CHECK (claimed_quantity IS NULL OR unit IS NOT NULL)
);

CREATE TABLE verification_evidence (
    id UUID PRIMARY KEY,
    verification_package_id UUID NOT NULL,
    document_revision_id UUID NOT NULL,
    evidence_type VARCHAR(100) NOT NULL,
    visibility_scope VARCHAR(80) NOT NULL,
    required_flag BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_verification_evidence_package FOREIGN KEY (verification_package_id) REFERENCES verification_packages(id),
    CONSTRAINT fk_verification_evidence_revision FOREIGN KEY (document_revision_id) REFERENCES document_revisions(id),
    CONSTRAINT uk_verification_evidence UNIQUE (verification_package_id, document_revision_id, evidence_type)
);

CREATE TABLE verification_workflow_instances (
    id UUID PRIMARY KEY,
    verification_package_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_verification_workflow_package FOREIGN KEY (verification_package_id) REFERENCES verification_packages(id),
    -- Workflow creation is JPA-backed while this typed link is JDBC-backed in the same transaction.
    -- Defer this FK until commit so PostgreSQL validates it after the persistence context flushes,
    -- without weakening referential integrity or introducing a second workflow persistence path.
    CONSTRAINT fk_verification_workflow_instance FOREIGN KEY (workflow_instance_id) REFERENCES workflow_instances(id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uk_verification_workflow_instance UNIQUE (workflow_instance_id),
    CONSTRAINT uk_verification_workflow_pair UNIQUE (verification_package_id, workflow_instance_id)
);

CREATE TABLE verification_decisions (
    id UUID PRIMARY KEY,
    verification_package_id UUID NOT NULL,
    verification_item_id UUID,
    actor_user_id UUID NOT NULL,
    actor_organization_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    decision VARCHAR(60) NOT NULL,
    accepted_quantity DECIMAL(19,4),
    rejected_quantity DECIMAL(19,4),
    unit VARCHAR(40),
    comments VARCHAR(2000),
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    prior_decision_id UUID,
    subject_version BIGINT NOT NULL,
    CONSTRAINT fk_verification_decisions_package FOREIGN KEY (verification_package_id) REFERENCES verification_packages(id),
    CONSTRAINT fk_verification_decisions_item FOREIGN KEY (verification_item_id) REFERENCES verification_items(id),
    CONSTRAINT fk_verification_decisions_actor FOREIGN KEY (actor_user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_verification_decisions_org FOREIGN KEY (actor_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_verification_decisions_workflow FOREIGN KEY (workflow_instance_id) REFERENCES workflow_instances(id),
    CONSTRAINT fk_verification_decisions_prior FOREIGN KEY (prior_decision_id) REFERENCES verification_decisions(id),
    CONSTRAINT ck_verification_decisions_accepted CHECK (accepted_quantity IS NULL OR accepted_quantity >= 0),
    CONSTRAINT ck_verification_decisions_rejected CHECK (rejected_quantity IS NULL OR rejected_quantity >= 0),
    CONSTRAINT ck_verification_decisions_quantity_unit CHECK ((accepted_quantity IS NULL AND rejected_quantity IS NULL) OR unit IS NOT NULL)
);

CREATE TABLE measurements (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    subject_resource_reference VARCHAR(500) NOT NULL,
    verification_package_id UUID NOT NULL,
    verification_item_id UUID NOT NULL,
    verification_decision_id UUID NOT NULL,
    unit VARCHAR(40) NOT NULL,
    period_from DATE,
    period_to DATE,
    submitted_quantity DECIMAL(19,4),
    measured_quantity DECIMAL(19,4) NOT NULL,
    accepted_quantity DECIMAL(19,4) NOT NULL,
    rejected_quantity DECIMAL(19,4) NOT NULL,
    status VARCHAR(40) NOT NULL,
    verified_by_user_id UUID NOT NULL,
    verified_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_measurements_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_measurements_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_measurements_package FOREIGN KEY (verification_package_id) REFERENCES verification_packages(id),
    CONSTRAINT fk_measurements_item FOREIGN KEY (verification_item_id) REFERENCES verification_items(id),
    CONSTRAINT fk_measurements_decision FOREIGN KEY (verification_decision_id) REFERENCES verification_decisions(id),
    CONSTRAINT fk_measurements_verified_by FOREIGN KEY (verified_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_measurements_decision UNIQUE (verification_decision_id),
    CONSTRAINT ck_measurements_dates CHECK (period_to IS NULL OR period_from IS NULL OR period_to >= period_from),
    CONSTRAINT ck_measurements_quantities CHECK (
        (submitted_quantity IS NULL OR submitted_quantity >= 0)
        AND measured_quantity >= 0
        AND accepted_quantity >= 0
        AND rejected_quantity >= 0
        AND accepted_quantity + rejected_quantity <= measured_quantity
    )
);

ALTER TABLE valuation_lines ADD COLUMN measurement_id UUID;
ALTER TABLE valuation_lines
    ADD CONSTRAINT fk_valuation_measurement FOREIGN KEY (measurement_id) REFERENCES measurements(id);
ALTER TABLE valuation_lines
    ADD CONSTRAINT uk_valuation_item_measurement UNIQUE (contract_item_id, measurement_id);

CREATE INDEX idx_verification_packages_scope_status ON verification_packages (project_id, scope_id, status);
CREATE INDEX idx_verification_items_package ON verification_items (verification_package_id);
CREATE INDEX idx_verification_evidence_package ON verification_evidence (verification_package_id);
CREATE INDEX idx_verification_decisions_package ON verification_decisions (verification_package_id, decided_at);
CREATE INDEX idx_measurements_scope_status ON measurements (project_id, scope_id, status);
CREATE INDEX idx_measurements_package ON measurements (verification_package_id);
CREATE INDEX idx_valuation_measurement ON valuation_lines (measurement_id);