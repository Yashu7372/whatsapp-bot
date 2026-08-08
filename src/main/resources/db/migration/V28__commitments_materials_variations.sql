-- =====================================================================
-- V28 - Commitments, materials and variations
-- Batch 3 builds on project controls and resource actual costs.
-- =====================================================================

-- Preserve values that pre-date the new Batch-3 fact tables. V27 may already have
-- rolled resource actual_cost_entries into budget_lines.actual_cost, so subtract
-- those detail facts when deriving the direct/manual baseline to avoid counting
-- the same resource actuals again when the V28 refresh function is installed.
ALTER TABLE budget_lines ADD COLUMN baseline_committed_cost NUMERIC(18,2) NOT NULL DEFAULT 0;
ALTER TABLE budget_lines ADD COLUMN baseline_actual_cost NUMERIC(18,2) NOT NULL DEFAULT 0;
ALTER TABLE budget_lines ADD COLUMN baseline_approved_changes NUMERIC(18,2) NOT NULL DEFAULT 0;
UPDATE budget_lines b
   SET baseline_committed_cost = b.committed_cost,
       baseline_actual_cost = GREATEST(
           b.actual_cost - COALESCE((
               SELECT SUM(a.amount)
                 FROM actual_cost_entries a
                WHERE a.budget_line_id = b.id
           ), 0),
           0
       ),
       baseline_approved_changes = b.approved_changes;

CREATE TABLE project_commitments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    budget_line_id UUID REFERENCES budget_lines(id) ON DELETE SET NULL,
    commitment_type VARCHAR(30) NOT NULL,
    reference_no VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    original_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    approved_changes NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL DEFAULT 'AED',
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    start_date DATE,
    end_date DATE,
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_commitment_type CHECK(commitment_type IN ('PURCHASE_ORDER','SUBCONTRACT','OTHER')),
    CONSTRAINT ck_commitment_amounts CHECK(original_amount >= 0 AND approved_changes >= 0),
    CONSTRAINT uk_project_commitment_ref UNIQUE(project_id, reference_no)
);
CREATE INDEX idx_commitments_project_org ON project_commitments(project_id, organization_id, status);
CREATE INDEX idx_commitments_budget_line ON project_commitments(budget_line_id);

CREATE TABLE material_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    commitment_id UUID REFERENCES project_commitments(id) ON DELETE SET NULL,
    budget_line_id UUID REFERENCES budget_lines(id) ON DELETE SET NULL,
    receipt_ref VARCHAR(100) NOT NULL,
    material_code VARCHAR(100),
    description VARCHAR(500) NOT NULL,
    receipt_date DATE NOT NULL,
    quantity NUMERIC(18,3) NOT NULL DEFAULT 0,
    unit VARCHAR(30),
    unit_cost NUMERIC(18,2) NOT NULL DEFAULT 0,
    amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency VARCHAR(10) NOT NULL DEFAULT 'AED',
    status VARCHAR(30) NOT NULL DEFAULT 'ACCEPTED',
    document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_material_receipt_values CHECK(quantity >= 0 AND unit_cost >= 0 AND amount >= 0),
    CONSTRAINT uk_material_receipt_ref UNIQUE(project_id, receipt_ref)
);
CREATE INDEX idx_material_receipts_project_date ON material_receipts(project_id, receipt_date, status);
CREATE INDEX idx_material_receipts_budget_line ON material_receipts(budget_line_id);

CREATE TABLE project_variations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    budget_line_id UUID REFERENCES budget_lines(id) ON DELETE SET NULL,
    variation_ref VARCHAR(100) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    source_type VARCHAR(40),
    source_document_id UUID REFERENCES documents(id) ON DELETE SET NULL,
    requested_amount NUMERIC(18,2) NOT NULL DEFAULT 0,
    approved_amount NUMERIC(18,2),
    currency VARCHAR(10) NOT NULL DEFAULT 'AED',
    status VARCHAR(30) NOT NULL DEFAULT 'PROPOSED',
    submitted_at TIMESTAMP,
    approved_at TIMESTAMP,
    approved_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_variation_requested CHECK(requested_amount >= 0),
    CONSTRAINT ck_variation_approved CHECK(approved_amount IS NULL OR approved_amount >= 0),
    CONSTRAINT uk_project_variation_ref UNIQUE(project_id, variation_ref)
);
CREATE INDEX idx_variations_project_status ON project_variations(project_id, status);
CREATE INDEX idx_variations_budget_line ON project_variations(budget_line_id);

CREATE OR REPLACE FUNCTION refresh_budget_line_commitment(p_budget_line UUID) RETURNS VOID AS $$
BEGIN
    IF p_budget_line IS NULL THEN RETURN; END IF;
    UPDATE budget_lines b
       SET committed_cost = b.baseline_committed_cost + COALESCE((
           SELECT SUM(c.original_amount + c.approved_changes)
             FROM project_commitments c
            WHERE c.budget_line_id = p_budget_line AND c.status = 'ACTIVE'
       ),0), updated_at = NOW()
     WHERE b.id = p_budget_line;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_refresh_commitment_budget() RETURNS TRIGGER AS $$
DECLARE
    target_line UUID;
BEGIN
    target_line := CASE WHEN TG_OP = 'DELETE' THEN OLD.budget_line_id ELSE NEW.budget_line_id END;
    PERFORM refresh_budget_line_commitment(target_line);
    IF TG_OP = 'UPDATE' AND OLD.budget_line_id IS DISTINCT FROM NEW.budget_line_id THEN
        PERFORM refresh_budget_line_commitment(OLD.budget_line_id);
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER project_commitments_budget_refresh
AFTER INSERT OR UPDATE OR DELETE ON project_commitments
FOR EACH ROW EXECUTE FUNCTION trg_refresh_commitment_budget();

-- Replace the Batch-2 INSERT-only rollup with a recomputation function so later
-- timesheet/equipment facts and Batch-3 accepted materials share one source-safe total.
CREATE OR REPLACE FUNCTION refresh_budget_line_actual(p_budget_line UUID) RETURNS VOID AS $$
BEGIN
    IF p_budget_line IS NULL THEN RETURN; END IF;
    UPDATE budget_lines b
       SET actual_cost = b.baseline_actual_cost
         + COALESCE((SELECT SUM(a.amount) FROM actual_cost_entries a WHERE a.budget_line_id = p_budget_line),0)
         + COALESCE((SELECT SUM(m.amount) FROM material_receipts m WHERE m.budget_line_id = p_budget_line AND m.status='ACCEPTED'),0),
           updated_at = NOW()
     WHERE b.id = p_budget_line;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION apply_actual_cost_to_budget_line() RETURNS TRIGGER AS $$
BEGIN
    PERFORM refresh_budget_line_actual(NEW.budget_line_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_refresh_material_actual() RETURNS TRIGGER AS $$
DECLARE
    target_line UUID;
BEGIN
    target_line := CASE WHEN TG_OP = 'DELETE' THEN OLD.budget_line_id ELSE NEW.budget_line_id END;
    PERFORM refresh_budget_line_actual(target_line);
    IF TG_OP = 'UPDATE' AND OLD.budget_line_id IS DISTINCT FROM NEW.budget_line_id THEN
        PERFORM refresh_budget_line_actual(OLD.budget_line_id);
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER material_receipts_actual_refresh
AFTER INSERT OR UPDATE OR DELETE ON material_receipts
FOR EACH ROW EXECUTE FUNCTION trg_refresh_material_actual();

CREATE OR REPLACE FUNCTION refresh_budget_line_variations(p_budget_line UUID) RETURNS VOID AS $$
BEGIN
    IF p_budget_line IS NULL THEN RETURN; END IF;
    UPDATE budget_lines b
       SET approved_changes = b.baseline_approved_changes + COALESCE((
           SELECT SUM(v.approved_amount)
             FROM project_variations v
            WHERE v.budget_line_id = p_budget_line AND v.status='APPROVED'
       ),0), updated_at = NOW()
     WHERE b.id = p_budget_line;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION trg_refresh_variation_budget() RETURNS TRIGGER AS $$
DECLARE
    target_line UUID;
BEGIN
    target_line := CASE WHEN TG_OP = 'DELETE' THEN OLD.budget_line_id ELSE NEW.budget_line_id END;
    PERFORM refresh_budget_line_variations(target_line);
    IF TG_OP = 'UPDATE' AND OLD.budget_line_id IS DISTINCT FROM NEW.budget_line_id THEN
        PERFORM refresh_budget_line_variations(OLD.budget_line_id);
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER project_variations_budget_refresh
AFTER INSERT OR UPDATE OR DELETE ON project_variations
FOR EACH ROW EXECUTE FUNCTION trg_refresh_variation_budget();
