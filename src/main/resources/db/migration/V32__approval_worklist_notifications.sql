CREATE TABLE workflow_notification_outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    document_id UUID REFERENCES documents(id) ON DELETE CASCADE,
    approval_id UUID REFERENCES document_approvals(id) ON DELETE CASCADE,
    approval_step_id UUID REFERENCES document_approval_steps(id) ON DELETE CASCADE,
    event_type VARCHAR(60) NOT NULL,
    target_user_id UUID REFERENCES tenant_users(id) ON DELETE CASCADE,
    target_organization_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    target_party_role VARCHAR(40),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP,
    CONSTRAINT ck_workflow_notification_status CHECK(status IN ('PENDING','DELIVERED','FAILED')),
    CONSTRAINT ck_workflow_notification_target CHECK(
        target_user_id IS NOT NULL OR target_organization_id IS NOT NULL OR target_party_role IS NOT NULL
    )
);
CREATE INDEX idx_workflow_notification_pending ON workflow_notification_outbox(tenant_id,status,created_at);
CREATE UNIQUE INDEX uk_workflow_notification_step_event
    ON workflow_notification_outbox(approval_step_id,event_type,
       coalesce(target_user_id,'00000000-0000-0000-0000-000000000000'::uuid),
       coalesce(target_organization_id,'00000000-0000-0000-0000-000000000000'::uuid),
       coalesce(target_party_role,''));
