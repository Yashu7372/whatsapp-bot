-- Project Controls foundation: contracts, versioned budgets/cost codes and immutable forecast history.
CREATE TABLE project_contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    participant_id UUID NOT NULL REFERENCES project_participants(id) ON DELETE CASCADE,
    contract_ref VARCHAR(100) NOT NULL,
    commercial_model VARCHAR(40) NOT NULL,
    original_value NUMERIC(18,2) NOT NULL DEFAULT 0,
    approved_variations NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL DEFAULT 'AED',
    start_date DATE,
    end_date DATE,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_project_contract_ref UNIQUE(project_id, contract_ref),
    CONSTRAINT ck_contract_value CHECK(original_value >= 0)
);
CREATE INDEX idx_project_contracts_project ON project_contracts(project_id, status);
CREATE INDEX idx_project_contracts_participant ON project_contracts(participant_id);

CREATE TABLE budget_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    label VARCHAR(150) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    effective_date DATE,
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_budget_project_version UNIQUE(project_id, version_no)
);
CREATE INDEX idx_budget_versions_project ON budget_versions(project_id, status);

CREATE TABLE budget_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    budget_version_id UUID NOT NULL REFERENCES budget_versions(id) ON DELETE CASCADE,
    parent_line_id UUID REFERENCES budget_lines(id) ON DELETE CASCADE,
    cost_code VARCHAR(80) NOT NULL,
    name VARCHAR(300) NOT NULL,
    original_budget NUMERIC(18,2) NOT NULL DEFAULT 0,
    approved_changes NUMERIC(18,2) NOT NULL DEFAULT 0,
    committed_cost NUMERIC(18,2) NOT NULL DEFAULT 0,
    actual_cost NUMERIC(18,2) NOT NULL DEFAULT 0,
    estimate_to_complete NUMERIC(18,2) NOT NULL DEFAULT 0,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_budget_line_code UNIQUE(budget_version_id, cost_code),
    CONSTRAINT ck_budget_line_values CHECK(original_budget >= 0 AND committed_cost >= 0 AND actual_cost >= 0 AND estimate_to_complete >= 0)
);
CREATE INDEX idx_budget_lines_version ON budget_lines(budget_version_id, sort_order);
CREATE INDEX idx_budget_lines_parent ON budget_lines(parent_line_id);

CREATE TABLE forecast_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    snapshot_date DATE NOT NULL,
    forecast_final_cost NUMERIC(18,2) NOT NULL,
    estimate_to_complete NUMERIC(18,2) NOT NULL DEFAULT 0,
    physical_progress_percent NUMERIC(5,2),
    schedule_progress_percent NUMERIC(5,2),
    notes TEXT,
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_forecast_progress CHECK((physical_progress_percent IS NULL OR physical_progress_percent BETWEEN 0 AND 100) AND (schedule_progress_percent IS NULL OR schedule_progress_percent BETWEEN 0 AND 100))
);
CREATE INDEX idx_forecast_snapshots_project ON forecast_snapshots(project_id, snapshot_date DESC);
CREATE INDEX idx_forecast_snapshots_source ON forecast_snapshots(source_organization_id, snapshot_date DESC);
