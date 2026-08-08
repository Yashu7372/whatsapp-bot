-- =====================================================================
-- V36 - Workflow runtime authority cleanup
-- =====================================================================
-- V31 introduced a BEFORE INSERT trigger that reconstructed workflow authority,
-- assignment and SLA fields from the workflow JSON. The application now persists
-- those fields explicitly. Keeping the trigger would silently overwrite runtime
-- state and, critically, start the SLA clock for every future sequential stage at
-- submission time.
DROP TRIGGER IF EXISTS trg_enrich_document_approval_step ON document_approval_steps;
DROP FUNCTION IF EXISTS enrich_document_approval_step();

-- The first actionable parallel group is materialised during approval creation.
-- Java stamps the current row. This narrowly scoped trigger gives later rows in
-- that same initial group the same activation semantics without touching future
-- sequential stages. All later activation is handled by ParallelApprovalRepository.advance().
CREATE OR REPLACE FUNCTION start_initial_parallel_step_sla()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_idx INTEGER;
    current_group VARCHAR(80);
BEGIN
    IF NEW.sla_hours IS NULL OR NEW.due_at IS NOT NULL THEN
        RETURN NEW;
    END IF;

    SELECT a.current_step
      INTO current_idx
      FROM document_approvals a
     WHERE a.id = NEW.approval_id;

    IF NEW.step_index = current_idx THEN
        NEW.due_at := NOW() + make_interval(hours => NEW.sla_hours);
        RETURN NEW;
    END IF;

    SELECT s.parallel_group
      INTO current_group
      FROM document_approval_steps s
     WHERE s.approval_id = NEW.approval_id
       AND s.step_index = current_idx;

    IF current_group IS NOT NULL AND NEW.parallel_group = current_group THEN
        NEW.due_at := NOW() + make_interval(hours => NEW.sla_hours);
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_start_initial_parallel_step_sla
BEFORE INSERT ON document_approval_steps
FOR EACH ROW EXECUTE FUNCTION start_initial_parallel_step_sla();
