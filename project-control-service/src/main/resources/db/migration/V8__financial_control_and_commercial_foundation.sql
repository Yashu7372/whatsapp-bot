CREATE TABLE contracts (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    payer_participant_id UUID NOT NULL,
    payee_participant_id UUID NOT NULL,
    contract_number VARCHAR(120) NOT NULL,
    contract_type VARCHAR(80) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    original_value DECIMAL(19,4) NOT NULL,
    visibility_policy VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_contracts_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_contracts_payer_participant FOREIGN KEY (payer_participant_id) REFERENCES project_participants(id),
    CONSTRAINT fk_contracts_payee_participant FOREIGN KEY (payee_participant_id) REFERENCES project_participants(id),
    CONSTRAINT uk_contracts_project_number UNIQUE (project_id, contract_number),
    CONSTRAINT ck_contracts_parties CHECK (payer_participant_id <> payee_participant_id),
    CONSTRAINT ck_contracts_original_value CHECK (original_value >= 0)
);

CREATE TABLE cost_structures (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owning_organization_id UUID,
    contract_id UUID,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    structure_type VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_cost_structures_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_cost_structures_org FOREIGN KEY (owning_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_cost_structures_contract FOREIGN KEY (contract_id) REFERENCES contracts(id),
    CONSTRAINT uk_cost_structures_project_code UNIQUE (project_id, code)
);

CREATE TABLE cost_nodes (
    id UUID PRIMARY KEY,
    cost_structure_id UUID NOT NULL,
    parent_cost_node_id UUID,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(240) NOT NULL,
    category VARCHAR(100),
    sort_order INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_cost_nodes_structure FOREIGN KEY (cost_structure_id) REFERENCES cost_structures(id),
    CONSTRAINT fk_cost_nodes_parent FOREIGN KEY (parent_cost_node_id) REFERENCES cost_nodes(id),
    CONSTRAINT uk_cost_nodes_structure_code UNIQUE (cost_structure_id, code)
);

CREATE TABLE cost_node_scope_links (
    id UUID PRIMARY KEY,
    cost_node_id UUID NOT NULL,
    scope_id UUID NOT NULL,
    allocation_percent DECIMAL(7,4),
    relationship_type VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_cost_node_scope_node FOREIGN KEY (cost_node_id) REFERENCES cost_nodes(id),
    CONSTRAINT fk_cost_node_scope_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT uk_cost_node_scope_link UNIQUE (cost_node_id, scope_id, relationship_type),
    CONSTRAINT ck_cost_node_scope_allocation CHECK (allocation_percent IS NULL OR (allocation_percent > 0 AND allocation_percent <= 100))
);

CREATE TABLE budget_versions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owning_organization_id UUID,
    cost_structure_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    baseline_type VARCHAR(32) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_by_user_id UUID NOT NULL,
    submitted_by_user_id UUID,
    approved_by_user_id UUID,
    submitted_at TIMESTAMP WITH TIME ZONE,
    approved_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_budget_versions_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_budget_versions_org FOREIGN KEY (owning_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_budget_versions_structure FOREIGN KEY (cost_structure_id) REFERENCES cost_structures(id),
    CONSTRAINT fk_budget_versions_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_budget_versions_submitted_by FOREIGN KEY (submitted_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_budget_versions_approved_by FOREIGN KEY (approved_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_budget_versions_structure_version UNIQUE (cost_structure_id, version_number)
);

CREATE TABLE budget_lines (
    id UUID PRIMARY KEY,
    budget_version_id UUID NOT NULL,
    cost_node_id UUID NOT NULL,
    scope_id UUID,
    amount DECIMAL(19,4) NOT NULL,
    notes VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_budget_lines_version FOREIGN KEY (budget_version_id) REFERENCES budget_versions(id),
    CONSTRAINT fk_budget_lines_node FOREIGN KEY (cost_node_id) REFERENCES cost_nodes(id),
    CONSTRAINT fk_budget_lines_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT ck_budget_lines_amount CHECK (amount >= 0)
);

CREATE TABLE commitments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owning_organization_id UUID NOT NULL,
    counterparty_organization_id UUID,
    contract_id UUID,
    scope_id UUID,
    cost_node_id UUID NOT NULL,
    commitment_reference VARCHAR(160) NOT NULL,
    committed_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_document_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_commitments_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_commitments_org FOREIGN KEY (owning_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_commitments_counterparty FOREIGN KEY (counterparty_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_commitments_contract FOREIGN KEY (contract_id) REFERENCES contracts(id),
    CONSTRAINT fk_commitments_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_commitments_node FOREIGN KEY (cost_node_id) REFERENCES cost_nodes(id),
    CONSTRAINT fk_commitments_source_revision FOREIGN KEY (source_document_revision_id) REFERENCES document_revisions(id),
    CONSTRAINT fk_commitments_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_commitments_project_reference UNIQUE (project_id, commitment_reference),
    CONSTRAINT ck_commitments_amount CHECK (committed_amount > 0)
);

CREATE TABLE actual_cost_entries (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owning_organization_id UUID NOT NULL,
    scope_id UUID,
    cost_node_id UUID NOT NULL,
    commitment_id UUID,
    source_type VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240) NOT NULL,
    counterparty_organization_id UUID,
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    accounting_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE,
    source_document_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_actual_cost_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_actual_cost_org FOREIGN KEY (owning_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_actual_cost_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_actual_cost_node FOREIGN KEY (cost_node_id) REFERENCES cost_nodes(id),
    CONSTRAINT fk_actual_cost_commitment FOREIGN KEY (commitment_id) REFERENCES commitments(id),
    CONSTRAINT fk_actual_cost_counterparty FOREIGN KEY (counterparty_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_actual_cost_source_revision FOREIGN KEY (source_document_revision_id) REFERENCES document_revisions(id),
    CONSTRAINT fk_actual_cost_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_actual_cost_source UNIQUE (project_id, owning_organization_id, source_type, source_reference),
    CONSTRAINT ck_actual_cost_amount CHECK (amount > 0)
);

CREATE TABLE forecast_entries (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    owning_organization_id UUID NOT NULL,
    scope_id UUID,
    cost_node_id UUID NOT NULL,
    forecast_period DATE NOT NULL,
    remaining_forecast_amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    basis VARCHAR(1000),
    status VARCHAR(32) NOT NULL,
    source_document_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_forecast_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_forecast_org FOREIGN KEY (owning_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_forecast_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_forecast_node FOREIGN KEY (cost_node_id) REFERENCES cost_nodes(id),
    CONSTRAINT fk_forecast_source_revision FOREIGN KEY (source_document_revision_id) REFERENCES document_revisions(id),
    CONSTRAINT fk_forecast_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT ck_forecast_amount CHECK (remaining_forecast_amount >= 0)
);

CREATE TABLE budget_control_decisions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    scope_id UUID,
    owning_organization_id UUID NOT NULL,
    cost_node_id UUID NOT NULL,
    request_resource_reference VARCHAR(240),
    current_budget DECIMAL(19,4) NOT NULL,
    actual DECIMAL(19,4) NOT NULL,
    open_commitment DECIMAL(19,4) NOT NULL,
    remaining_forecast DECIMAL(19,4) NOT NULL,
    proposed_exposure DECIMAL(19,4) NOT NULL,
    available_before DECIMAL(19,4) NOT NULL,
    available_after DECIMAL(19,4) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    actor_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_budget_decision_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_budget_decision_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_budget_decision_org FOREIGN KEY (owning_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_budget_decision_node FOREIGN KEY (cost_node_id) REFERENCES cost_nodes(id),
    CONSTRAINT fk_budget_decision_actor FOREIGN KEY (actor_user_id) REFERENCES user_accounts(id)
);

CREATE TABLE contract_items (
    id UUID PRIMARY KEY,
    contract_id UUID NOT NULL,
    scope_id UUID,
    item_code VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    valuation_method VARCHAR(40) NOT NULL,
    unit VARCHAR(40),
    planned_quantity DECIMAL(19,4),
    rate DECIMAL(19,4),
    contract_value DECIMAL(19,4) NOT NULL,
    due_date DATE,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_contract_items_contract FOREIGN KEY (contract_id) REFERENCES contracts(id),
    CONSTRAINT fk_contract_items_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT uk_contract_items_code UNIQUE (contract_id, item_code),
    CONSTRAINT ck_contract_items_value CHECK (contract_value >= 0)
);

CREATE TABLE valuation_lines (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    scope_id UUID,
    contract_item_id UUID NOT NULL,
    valuation_number VARCHAR(160) NOT NULL,
    source_type VARCHAR(80) NOT NULL,
    source_reference VARCHAR(240),
    source_document_revision_id UUID,
    unit VARCHAR(40),
    accepted_quantity DECIMAL(19,4),
    rate DECIMAL(19,4),
    gross_value DECIMAL(19,4) NOT NULL,
    prior_value DECIMAL(19,4) NOT NULL,
    current_value DECIMAL(19,4) NOT NULL,
    cumulative_value DECIMAL(19,4) NOT NULL,
    retention DECIMAL(19,4) NOT NULL,
    other_deductions DECIMAL(19,4) NOT NULL,
    eligible_value DECIMAL(19,4) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_valuation_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_valuation_contract FOREIGN KEY (contract_id) REFERENCES contracts(id),
    CONSTRAINT fk_valuation_scope FOREIGN KEY (scope_id) REFERENCES project_scopes(id),
    CONSTRAINT fk_valuation_item FOREIGN KEY (contract_item_id) REFERENCES contract_items(id),
    CONSTRAINT fk_valuation_source_revision FOREIGN KEY (source_document_revision_id) REFERENCES document_revisions(id),
    CONSTRAINT fk_valuation_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_valuation_project_number UNIQUE (project_id, valuation_number),
    CONSTRAINT ck_valuation_values CHECK (gross_value >= 0 AND prior_value >= 0 AND current_value >= 0 AND cumulative_value >= 0 AND retention >= 0 AND other_deductions >= 0 AND eligible_value >= 0)
);

CREATE TABLE payment_applications (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    application_number VARCHAR(120) NOT NULL,
    period_from DATE,
    period_to DATE,
    due_date DATE,
    claimed_amount DECIMAL(19,4) NOT NULL,
    certified_amount DECIMAL(19,4),
    status VARCHAR(32) NOT NULL,
    submitted_by_user_id UUID,
    certified_by_user_id UUID,
    submitted_at TIMESTAMP WITH TIME ZONE,
    certified_at TIMESTAMP WITH TIME ZONE,
    source_document_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_payment_app_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_payment_app_contract FOREIGN KEY (contract_id) REFERENCES contracts(id),
    CONSTRAINT fk_payment_app_submitted_by FOREIGN KEY (submitted_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_payment_app_certified_by FOREIGN KEY (certified_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT fk_payment_app_source_revision FOREIGN KEY (source_document_revision_id) REFERENCES document_revisions(id),
    CONSTRAINT fk_payment_app_created_by FOREIGN KEY (created_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_payment_app_project_number UNIQUE (project_id, application_number),
    CONSTRAINT ck_payment_app_dates CHECK (period_to IS NULL OR period_from IS NULL OR period_to >= period_from),
    CONSTRAINT ck_payment_app_amounts CHECK (claimed_amount >= 0 AND (certified_amount IS NULL OR certified_amount >= 0))
);

CREATE TABLE payment_application_lines (
    id UUID PRIMARY KEY,
    payment_application_id UUID NOT NULL,
    valuation_line_id UUID NOT NULL,
    claimed_value DECIMAL(19,4) NOT NULL,
    certified_value DECIMAL(19,4),
    certification_reason VARCHAR(1000),
    CONSTRAINT fk_payment_app_lines_app FOREIGN KEY (payment_application_id) REFERENCES payment_applications(id),
    CONSTRAINT fk_payment_app_lines_valuation FOREIGN KEY (valuation_line_id) REFERENCES valuation_lines(id),
    CONSTRAINT uk_payment_app_line UNIQUE (payment_application_id, valuation_line_id),
    CONSTRAINT ck_payment_app_line_amounts CHECK (claimed_value >= 0 AND (certified_value IS NULL OR certified_value >= 0))
);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    payment_application_id UUID NOT NULL,
    payment_reference VARCHAR(160) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    paid_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payer_organization_id UUID NOT NULL,
    payee_organization_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_document_revision_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    recorded_by_user_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_payments_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_payments_contract FOREIGN KEY (contract_id) REFERENCES contracts(id),
    CONSTRAINT fk_payments_app FOREIGN KEY (payment_application_id) REFERENCES payment_applications(id),
    CONSTRAINT fk_payments_payer FOREIGN KEY (payer_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_payments_payee FOREIGN KEY (payee_organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_payments_source_revision FOREIGN KEY (source_document_revision_id) REFERENCES document_revisions(id),
    CONSTRAINT fk_payments_recorded_by FOREIGN KEY (recorded_by_user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_payments_project_reference UNIQUE (project_id, payment_reference),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_parties CHECK (payer_organization_id <> payee_organization_id)
);

CREATE INDEX idx_cost_structures_project_org ON cost_structures (project_id, owning_organization_id);
CREATE INDEX idx_cost_nodes_structure_parent ON cost_nodes (cost_structure_id, parent_cost_node_id, sort_order);
CREATE INDEX idx_cost_node_scope_scope ON cost_node_scope_links (scope_id, cost_node_id);
CREATE INDEX idx_budget_versions_structure_status ON budget_versions (cost_structure_id, status, version_number);
CREATE INDEX idx_budget_lines_node_scope ON budget_lines (cost_node_id, scope_id);
CREATE INDEX idx_commitments_node_status ON commitments (cost_node_id, status);
CREATE INDEX idx_commitments_scope ON commitments (project_id, owning_organization_id, scope_id);
CREATE INDEX idx_actual_cost_node_date ON actual_cost_entries (cost_node_id, accounting_date, status);
CREATE INDEX idx_actual_cost_scope ON actual_cost_entries (project_id, owning_organization_id, scope_id);
CREATE INDEX idx_forecast_node_period ON forecast_entries (cost_node_id, forecast_period, status);
CREATE INDEX idx_forecast_scope ON forecast_entries (project_id, owning_organization_id, scope_id);
CREATE INDEX idx_budget_decisions_node_time ON budget_control_decisions (cost_node_id, created_at);
CREATE INDEX idx_contracts_project_parties ON contracts (project_id, payer_participant_id, payee_participant_id);
CREATE INDEX idx_contract_items_scope ON contract_items (scope_id, contract_id);
CREATE INDEX idx_valuation_contract_scope ON valuation_lines (contract_id, scope_id, status);
CREATE INDEX idx_payment_app_contract_status ON payment_applications (contract_id, status, due_date);
CREATE INDEX idx_payments_contract_date ON payments (contract_id, paid_at, status);
