-- Formal workflow authority and assignment model.
ALTER TABLE document_approval_steps ADD COLUMN authority_type VARCHAR(40) NOT NULL DEFAULT 'TECHNICAL_REVIEW';
ALTER TABLE document_approval_steps ADD COLUMN assignment_type VARCHAR(30) NOT NULL DEFAULT 'USER';
ALTER TABLE document_approval_steps ADD COLUMN assignment_organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL;
ALTER TABLE document_approval_steps ADD COLUMN assignment_party_role VARCHAR(40);
ALTER TABLE document_approval_steps ADD COLUMN required BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE document_approval_steps ADD COLUMN parallel_group VARCHAR(80);
ALTER TABLE document_approval_steps ADD COLUMN sla_hours INTEGER;
ALTER TABLE document_approval_steps ADD COLUMN due_at TIMESTAMP;
ALTER TABLE document_approval_steps ADD COLUMN escalated_at TIMESTAMP;

ALTER TABLE document_approval_steps ADD CONSTRAINT ck_approval_authority
    CHECK (authority_type IN ('INTERNAL_REVIEW','TECHNICAL_REVIEW','CLIENT_APPROVAL','COMMERCIAL_CERTIFICATION'));
ALTER TABLE document_approval_steps ADD CONSTRAINT ck_approval_assignment
    CHECK (assignment_type IN ('USER','ORGANIZATION','PARTY_ROLE'));
ALTER TABLE document_approval_steps ADD CONSTRAINT ck_approval_sla CHECK (sla_hours IS NULL OR sla_hours > 0);

CREATE INDEX idx_approval_steps_due ON document_approval_steps(due_at) WHERE decision IS NULL;
CREATE INDEX idx_approval_steps_org ON document_approval_steps(assignment_organization_id) WHERE decision IS NULL;
CREATE INDEX idx_approval_steps_party ON document_approval_steps(assignment_party_role) WHERE decision IS NULL;

-- Existing Java workflow seeding creates one row per JSON step. This trigger enriches that row
-- from the same immutable workflow definition, so authority/SLA semantics do not depend on the UI.
CREATE OR REPLACE FUNCTION enrich_document_approval_step()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    step_json JSONB;
BEGIN
    SELECT w.steps -> NEW.step_index
      INTO step_json
      FROM document_approvals a
      JOIN document_control_workflows w ON w.id = a.workflow_id
     WHERE a.id = NEW.approval_id;

    IF step_json IS NULL THEN
        RETURN NEW;
    END IF;

    NEW.authority_type := COALESCE(NULLIF(upper(step_json->>'authority'),''), 'TECHNICAL_REVIEW');
    NEW.assignment_type := COALESCE(NULLIF(upper(step_json->>'assignmentType'),''),
                                    CASE WHEN NULLIF(step_json->>'reviewerEmail','') IS NOT NULL THEN 'USER' ELSE 'PARTY_ROLE' END);
    NEW.assignment_organization_id := NULLIF(step_json->>'organizationId','')::uuid;
    NEW.assignment_party_role := NULLIF(upper(step_json->>'partyRole'),'');
    NEW.required := COALESCE((step_json->>'required')::boolean, TRUE);
    NEW.parallel_group := NULLIF(step_json->>'parallelGroup','');
    NEW.sla_hours := NULLIF(step_json->>'slaHours','')::integer;
    IF NEW.sla_hours IS NOT NULL THEN
        NEW.due_at := NOW() + make_interval(hours => NEW.sla_hours);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enrich_document_approval_step
BEFORE INSERT ON document_approval_steps
FOR EACH ROW EXECUTE FUNCTION enrich_document_approval_step();
