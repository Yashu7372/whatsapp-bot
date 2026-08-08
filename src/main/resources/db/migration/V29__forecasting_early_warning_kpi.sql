-- =====================================================================
-- V29 - Deterministic forecasting, early warning and consultant KPI
-- Derived snapshots never replace source commercial facts.
-- =====================================================================

CREATE TABLE control_forecast_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    snapshot_date DATE NOT NULL,
    current_budget NUMERIC(18,2) NOT NULL DEFAULT 0,
    actual_cost NUMERIC(18,2) NOT NULL DEFAULT 0,
    committed_cost NUMERIC(18,2) NOT NULL DEFAULT 0,
    estimate_to_complete NUMERIC(18,2) NOT NULL DEFAULT 0,
    pending_variation_exposure NUMERIC(18,2) NOT NULL DEFAULT 0,
    base_eac NUMERIC(18,2) NOT NULL DEFAULT 0,
    exposure_eac NUMERIC(18,2) NOT NULL DEFAULT 0,
    forecast_variance NUMERIC(18,2) NOT NULL DEFAULT 0,
    physical_progress_percent NUMERIC(6,2),
    schedule_progress_percent NUMERIC(6,2),
    cost_consumption_percent NUMERIC(6,2),
    source_version VARCHAR(30) NOT NULL DEFAULT 'CONTROL_V1',
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_control_forecast_project_day UNIQUE(project_id, snapshot_date)
);
CREATE INDEX idx_control_forecast_project_date ON control_forecast_snapshots(project_id, snapshot_date DESC);

CREATE TABLE early_warning_signals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    forecast_snapshot_id UUID NOT NULL REFERENCES control_forecast_snapshots(id) ON DELETE CASCADE,
    signal_code VARCHAR(80) NOT NULL,
    severity VARCHAR(20) NOT NULL, -- INFO | ATTENTION | CRITICAL
    title VARCHAR(300) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    metric_value NUMERIC(18,4),
    threshold_value NUMERIC(18,4),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_warning_severity CHECK(severity IN ('INFO','ATTENTION','CRITICAL')),
    CONSTRAINT uk_warning_snapshot_code UNIQUE(forecast_snapshot_id, signal_code)
);
CREATE INDEX idx_warning_project_severity ON early_warning_signals(project_id, severity, created_at DESC);

CREATE TABLE consultant_kpi_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    snapshot_date DATE NOT NULL,
    document_sla_health NUMERIC(6,2) NOT NULL DEFAULT 100,
    forecast_alignment NUMERIC(6,2) NOT NULL DEFAULT 100,
    overall_control_health NUMERIC(6,2) NOT NULL DEFAULT 100,
    overdue_documents INTEGER NOT NULL DEFAULT 0,
    due_documents INTEGER NOT NULL DEFAULT 0,
    latest_party_forecast NUMERIC(18,2),
    control_forecast NUMERIC(18,2),
    forecast_gap NUMERIC(18,2),
    methodology_version VARCHAR(30) NOT NULL DEFAULT 'KPI_V1',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_consultant_kpi_project_org_day UNIQUE(project_id, organization_id, snapshot_date),
    CONSTRAINT ck_consultant_kpi_scores CHECK(document_sla_health BETWEEN 0 AND 100 AND forecast_alignment BETWEEN 0 AND 100 AND overall_control_health BETWEEN 0 AND 100)
);
CREATE INDEX idx_consultant_kpi_project_date ON consultant_kpi_snapshots(project_id, snapshot_date DESC);
