-- Durable multi-channel notification delivery for document control/workflow events.
ALTER TABLE tenant_users ADD COLUMN notification_phone VARCHAR(50);
ALTER TABLE tenant_users ADD COLUMN email_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE tenant_users ADD COLUMN whatsapp_notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE workflow_notification_outbox ADD COLUMN transmittal_id UUID REFERENCES document_transmittals(id) ON DELETE CASCADE;
ALTER TABLE workflow_notification_outbox ADD COLUMN dispatched_at TIMESTAMP;

CREATE TABLE workflow_in_app_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES tenant_users(id) ON DELETE CASCADE,
    outbox_id UUID NOT NULL REFERENCES workflow_notification_outbox(id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    document_id UUID REFERENCES documents(id) ON DELETE CASCADE,
    transmittal_id UUID REFERENCES document_transmittals(id) ON DELETE CASCADE,
    event_type VARCHAR(60) NOT NULL,
    title VARCHAR(240) NOT NULL,
    body TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_in_app_notification_user_event UNIQUE(outbox_id,user_id)
);
CREATE INDEX idx_in_app_notification_user ON workflow_in_app_notifications(tenant_id,user_id,read_at,created_at DESC);

CREATE TABLE workflow_notification_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    outbox_id UUID NOT NULL REFERENCES workflow_notification_outbox(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES tenant_users(id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL,
    destination VARCHAR(320) NOT NULL,
    subject VARCHAR(240),
    body TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMP,
    last_error TEXT,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_notification_delivery_channel CHECK(channel IN ('EMAIL','WHATSAPP')),
    CONSTRAINT ck_notification_delivery_status CHECK(status IN ('PENDING','PROCESSING','SENT','FAILED','DEAD','SKIPPED')),
    CONSTRAINT uk_notification_delivery_user_channel UNIQUE(outbox_id,user_id,channel)
);
CREATE INDEX idx_notification_delivery_pending ON workflow_notification_deliveries(status,next_attempt_at,created_at);

CREATE UNIQUE INDEX uk_notification_approval_result
    ON workflow_notification_outbox(approval_id,event_type,target_user_id)
    WHERE approval_id IS NOT NULL AND approval_step_id IS NULL AND target_user_id IS NOT NULL;
-- An issued transmittal should notify each recipient company once. Acknowledgements are deliberately
-- not covered by this uniqueness key because every recipient acknowledgement is a distinct event for the sender.
CREATE UNIQUE INDEX uk_notification_transmittal_issued
    ON workflow_notification_outbox(transmittal_id,event_type,target_organization_id)
    WHERE transmittal_id IS NOT NULL AND event_type='TRANSMITTAL_ISSUED' AND target_organization_id IS NOT NULL;

-- The first active workflow stage is seeded after the approval header. Emit assignment only for
-- the current stage (or peers in its parallel group), never for future stages.
CREATE OR REPLACE FUNCTION notify_new_approval_step()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    a document_approvals%ROWTYPE;
    current_group VARCHAR(80);
    target_user UUID;
BEGIN
    SELECT * INTO a FROM document_approvals WHERE id=NEW.approval_id;
    IF a.id IS NULL OR a.status <> 'PENDING' THEN RETURN NEW; END IF;
    SELECT parallel_group INTO current_group FROM document_approval_steps
      WHERE approval_id=a.id AND step_index=a.current_step LIMIT 1;
    IF NEW.step_index <> a.current_step AND (current_group IS NULL OR NEW.parallel_group IS DISTINCT FROM current_group) THEN
        RETURN NEW;
    END IF;
    IF NEW.assignment_type='USER' THEN
        SELECT id INTO target_user FROM tenant_users WHERE tenant_id=a.tenant_id AND active=true AND lower(email)=lower(NEW.reviewer_email) LIMIT 1;
        IF target_user IS NULL THEN RETURN NEW; END IF;
    END IF;
    INSERT INTO workflow_notification_outbox(
        tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
        target_user_id,target_organization_id,target_party_role,payload)
    SELECT a.tenant_id,d.project_id,a.document_id,a.id,NEW.id,'APPROVAL_ASSIGNED',
           CASE WHEN NEW.assignment_type='USER' THEN target_user END,
           CASE WHEN NEW.assignment_type='ORGANIZATION' THEN NEW.assignment_organization_id END,
           CASE WHEN NEW.assignment_type='PARTY_ROLE' THEN NEW.assignment_party_role END,
           jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',NEW.step_name,
                              'authority',NEW.authority_type,'dueAt',NEW.due_at,'parallelGroup',NEW.parallel_group)
      FROM documents d WHERE d.id=a.document_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_notify_new_approval_step AFTER INSERT ON document_approval_steps
FOR EACH ROW EXECUTE FUNCTION notify_new_approval_step();

CREATE OR REPLACE FUNCTION notify_approval_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='PENDING' AND NEW.current_step IS DISTINCT FROM OLD.current_step THEN
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
            target_user_id,target_organization_id,target_party_role,payload)
        SELECT NEW.tenant_id,d.project_id,NEW.document_id,NEW.id,s.id,'APPROVAL_ASSIGNED',
               CASE WHEN s.assignment_type='USER' THEN u.id END,
               CASE WHEN s.assignment_type='ORGANIZATION' THEN s.assignment_organization_id END,
               CASE WHEN s.assignment_type='PARTY_ROLE' THEN s.assignment_party_role END,
               jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',s.step_name,
                                  'authority',s.authority_type,'dueAt',s.due_at,'parallelGroup',s.parallel_group)
          FROM documents d
          JOIN document_approval_steps current_s ON current_s.approval_id=NEW.id AND current_s.step_index=NEW.current_step
          JOIN document_approval_steps s ON s.approval_id=NEW.id AND s.decision IS NULL
          LEFT JOIN tenant_users u ON u.tenant_id=NEW.tenant_id AND u.active=true AND lower(u.email)=lower(s.reviewer_email)
         WHERE d.id=NEW.document_id
           AND (s.step_index=NEW.current_step OR (current_s.parallel_group IS NOT NULL AND s.parallel_group=current_s.parallel_group))
           AND (s.assignment_type<>'USER' OR u.id IS NOT NULL)
        ON CONFLICT DO NOTHING;
    END IF;

    IF NEW.status IN ('APPROVED','REJECTED') AND NEW.status IS DISTINCT FROM OLD.status AND NEW.initiated_by IS NOT NULL THEN
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,document_id,approval_id,event_type,target_user_id,payload)
        SELECT NEW.tenant_id,d.project_id,NEW.document_id,NEW.id,'APPROVAL_RESULT',NEW.initiated_by,
               jsonb_build_object('documentCode',d.document_code,'title',d.title,'status',NEW.status,
                                  'completedAt',NEW.completed_at,'reviewOutcome',d.review_outcome)
          FROM documents d WHERE d.id=NEW.document_id
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_notify_approval_transition AFTER UPDATE OF current_step,status ON document_approvals
FOR EACH ROW EXECUTE FUNCTION notify_approval_transition();

CREATE OR REPLACE FUNCTION notify_transmittal_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status='ISSUED' AND OLD.status='DRAFT' THEN
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,transmittal_id,event_type,target_organization_id,payload)
        SELECT NEW.tenant_id,NEW.project_id,NEW.id,'TRANSMITTAL_ISSUED',r.recipient_organization_id,
               jsonb_build_object('transmittalNo',NEW.transmittal_no,'subject',NEW.subject,'purpose',NEW.purpose,
                                  'senderOrganizationId',NEW.sender_organization_id,'issuedAt',NEW.issued_at)
          FROM document_transmittal_recipients r WHERE r.transmittal_id=NEW.id
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_notify_transmittal_transition AFTER UPDATE OF status ON document_transmittals
FOR EACH ROW EXECUTE FUNCTION notify_transmittal_transition();

CREATE OR REPLACE FUNCTION notify_transmittal_acknowledgement()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE sender_org UUID; project UUID; no_text VARCHAR(100); subject_text VARCHAR(300); tenant UUID;
BEGIN
    IF OLD.acknowledged_at IS NULL AND NEW.acknowledged_at IS NOT NULL THEN
        SELECT sender_organization_id,project_id,transmittal_no,subject,tenant_id
          INTO sender_org,project,no_text,subject_text,tenant
          FROM document_transmittals WHERE id=NEW.transmittal_id;
        INSERT INTO workflow_notification_outbox(
            tenant_id,project_id,transmittal_id,event_type,target_organization_id,payload)
        VALUES(tenant,project,NEW.transmittal_id,'TRANSMITTAL_ACKNOWLEDGED',sender_org,
               jsonb_build_object('transmittalNo',no_text,'subject',subject_text,
                                  'recipientOrganizationId',NEW.recipient_organization_id,'acknowledgedAt',NEW.acknowledged_at));
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER trg_notify_transmittal_acknowledgement AFTER UPDATE OF acknowledged_at ON document_transmittal_recipients
FOR EACH ROW EXECUTE FUNCTION notify_transmittal_acknowledgement();
