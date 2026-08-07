ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS business_hours VARCHAR(200) NOT NULL DEFAULT 'Sat-Thu 9am-9pm',
    ADD COLUMN IF NOT EXISTS crm_business_type VARCHAR(50) NOT NULL DEFAULT 'other',
    ADD COLUMN IF NOT EXISTS whatsapp_number VARCHAR(100),
    ADD COLUMN IF NOT EXISTS faq_json JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS language VARCHAR(20) NOT NULL DEFAULT 'en';

ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS unread_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_message_preview TEXT;

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS intent VARCHAR(120),
    ADD COLUMN IF NOT EXISTS confidence_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS action_type VARCHAR(120),
    ADD COLUMN IF NOT EXISTS buttons_json TEXT;

CREATE INDEX IF NOT EXISTS idx_conversations_tenant_last_message_at
    ON conversations(tenant_id, last_message_at DESC);
