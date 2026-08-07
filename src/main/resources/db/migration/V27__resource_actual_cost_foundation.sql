-- =====================================================================
-- V27 - Resource and actual cost foundation
-- Batch 2 builds on V26 project controls without changing prior tables.
-- Rates are commercial data and never exposed directly to ordinary workers.
-- =====================================================================

CREATE TABLE project_resources (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id         UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    resource_type      VARCHAR(30) NOT NULL, -- PERSON | EQUIPMENT | MACHINE | VEHICLE
    resource_code      VARCHAR(80) NOT NULL,
    display_name       VARCHAR(300) NOT NULL,
    user_id            UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_project_resource_code UNIQUE(project_id, organization_id, resource_code),
    CONSTRAINT ck_project_resource_type CHECK(resource_type IN ('PERSON','EQUIPMENT','MACHINE','VEHICLE'))
);
CREATE INDEX idx_project_resources_project_org ON project_resources(project_id, organization_id, active);

CREATE TABLE resource_rates (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id         UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    resource_id        UUID NOT NULL REFERENCES project_resources(id) ON DELETE CASCADE,
    rate_type          VARCHAR(30) NOT NULL, -- HOURLY | DAILY | MONTHLY | UNIT
    rate_amount        NUMERIC(18,2) NOT NULL,
    currency           VARCHAR(10) NOT NULL DEFAULT 'AED',
    effective_from     DATE NOT NULL,
    effective_to       DATE,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_resource_rate_amount CHECK(rate_amount >= 0),
    CONSTRAINT ck_resource_rate_type CHECK(rate_type IN ('HOURLY','DAILY','MONTHLY','UNIT'))
);
CREATE INDEX idx_resource_rates_effective ON resource_rates(resource_id, effective_from, effective_to);

CREATE TABLE timesheets (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id         UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    resource_id        UUID NOT NULL REFERENCES project_resources(id) ON DELETE CASCADE,
    work_date          DATE NOT NULL,
    hours              NUMERIC(8,2) NOT NULL,
    status             VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED', -- SUBMITTED | APPROVED | REJECTED
    description        VARCHAR(500),
    approved_by        UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    approved_at        TIMESTAMP,
    created_by         UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_timesheet_hours CHECK(hours >= 0 AND hours <= 24),
    CONSTRAINT uk_timesheet_resource_day UNIQUE(project_id, resource_id, work_date)
);
CREATE INDEX idx_timesheets_project_date ON timesheets(project_id, work_date, status);

CREATE TABLE equipment_usage (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id         UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    resource_id        UUID NOT NULL REFERENCES project_resources(id) ON DELETE CASCADE,
    usage_date         DATE NOT NULL,
    running_hours      NUMERIC(10,2) NOT NULL DEFAULT 0,
    quantity           NUMERIC(18,3),
    status             VARCHAR(30) NOT NULL DEFAULT 'APPROVED',
    notes              VARCHAR(500),
    created_by         UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_equipment_running_hours CHECK(running_hours >= 0 AND running_hours <= 24),
    CONSTRAINT uk_equipment_usage_resource_day UNIQUE(project_id, resource_id, usage_date)
);
CREATE INDEX idx_equipment_usage_project_date ON equipment_usage(project_id, usage_date, status);

CREATE TABLE actual_cost_entries (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id         UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id    UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    budget_line_id     UUID REFERENCES budget_lines(id) ON DELETE SET NULL,
    resource_id        UUID REFERENCES project_resources(id) ON DELETE SET NULL,
    source_type        VARCHAR(40) NOT NULL, -- TIMESHEET | EQUIPMENT_USAGE | MANUAL
    source_id          UUID,
    cost_date          DATE NOT NULL,
    quantity           NUMERIC(18,3) NOT NULL DEFAULT 0,
    unit_rate          NUMERIC(18,2) NOT NULL DEFAULT 0,
    amount             NUMERIC(18,2) NOT NULL,
    currency           VARCHAR(10) NOT NULL DEFAULT 'AED',
    description        VARCHAR(500),
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_actual_cost_non_negative CHECK(amount >= 0),
    CONSTRAINT uk_actual_cost_source UNIQUE(source_type, source_id)
);
CREATE INDEX idx_actual_cost_project_date ON actual_cost_entries(project_id, cost_date);
CREATE INDEX idx_actual_cost_budget_line ON actual_cost_entries(budget_line_id);
CREATE INDEX idx_actual_cost_org ON actual_cost_entries(organization_id);
