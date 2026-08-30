CREATE TABLE channel_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    external_address VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_channel_identities_user FOREIGN KEY (user_id) REFERENCES user_accounts(id),
    CONSTRAINT uk_channel_identities_address UNIQUE (channel_type, external_address)
);

CREATE TABLE channel_contexts (
    channel_identity_id UUID PRIMARY KEY,
    active_workflow_instance_id UUID,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_channel_context_identity FOREIGN KEY (channel_identity_id) REFERENCES channel_identities(id),
    CONSTRAINT fk_channel_context_workflow FOREIGN KEY (active_workflow_instance_id) REFERENCES workflow_instances(id)
);

CREATE TABLE channel_notifications (
    id UUID PRIMARY KEY,
    channel_identity_id UUID NOT NULL,
    workflow_step_instance_id UUID NOT NULL,
    workflow_instance_id UUID NOT NULL,
    status VARCHAR(48) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_channel_notification_identity FOREIGN KEY (channel_identity_id) REFERENCES channel_identities(id),
    CONSTRAINT fk_channel_notification_step FOREIGN KEY (workflow_step_instance_id) REFERENCES workflow_step_instances(id),
    CONSTRAINT fk_channel_notification_workflow FOREIGN KEY (workflow_instance_id) REFERENCES workflow_instances(id),
    CONSTRAINT uk_channel_notification_step UNIQUE (channel_identity_id, workflow_step_instance_id)
);

CREATE TABLE channel_inbound_messages (
    id UUID PRIMARY KEY,
    channel_identity_id UUID NOT NULL,
    provider_message_id VARCHAR(200) NOT NULL,
    message_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_channel_inbound_identity FOREIGN KEY (channel_identity_id) REFERENCES channel_identities(id),
    CONSTRAINT uk_channel_inbound_provider_message UNIQUE (provider_message_id)
);

CREATE INDEX idx_channel_identity_user ON channel_identities (user_id, status);
CREATE INDEX idx_channel_notification_workflow ON channel_notifications (workflow_instance_id, status);
CREATE INDEX idx_channel_inbound_identity ON channel_inbound_messages (channel_identity_id, received_at);
