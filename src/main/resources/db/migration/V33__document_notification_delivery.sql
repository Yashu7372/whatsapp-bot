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
CREATE UNIQUE INDEX uk_notification_transmittal_target
    ON workflow_notification_outbox(transmittal_id,event_type,target_organization_id)
    WHERE transmittal_id IS NOT NULL AND target_organization_id IS NOT NULL;
