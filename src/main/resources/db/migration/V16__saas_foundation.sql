-- =========================================================
-- V16 - SaaS Foundation: Feature Flags, Plans, Subscriptions
-- =========================================================

-- Feature flag registry per tenant
CREATE TABLE tenant_features (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    feature_code VARCHAR(100) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    config       JSONB,
    enabled_at   TIMESTAMP,
    disabled_at  TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, feature_code)
);

CREATE INDEX idx_tenant_features_tenant_code ON tenant_features(tenant_id, feature_code, enabled);

-- Subscription plan definitions
CREATE TABLE subscription_plans (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(100)   UNIQUE NOT NULL,
    name          VARCHAR(200)   NOT NULL,
    monthly_price NUMERIC(12, 2),
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    limits        JSONB          NOT NULL DEFAULT '{}',
    created_at    TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Per-plan feature entitlements
CREATE TABLE plan_features (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_code    VARCHAR(100) NOT NULL,
    feature_code VARCHAR(100) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    limits       JSONB,
    UNIQUE (plan_code, feature_code)
);

CREATE INDEX idx_plan_features_plan ON plan_features(plan_code, enabled);

-- Tenant subscription records
CREATE TABLE tenant_subscriptions (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    plan_code      VARCHAR(100) NOT NULL,
    status         VARCHAR(50)  NOT NULL DEFAULT 'TRIAL',
    started_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at     TIMESTAMP,
    trial_ends_at  TIMESTAMP,
    cancelled_at   TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_subscriptions_tenant  ON tenant_subscriptions(tenant_id, status);
CREATE INDEX idx_tenant_subscriptions_status  ON tenant_subscriptions(status, expires_at);

-- =========================================================
-- Seed plans
-- =========================================================

INSERT INTO subscription_plans (code, name, monthly_price, limits) VALUES
    ('STARTER',      'Starter',      29.00,  '{"maxUsers":2,"maxDocuments":100,"maxMediaStorageGb":5,"maxGeneratedVideosPerMonth":10,"maxScheduledPostsPerMonth":50,"maxRenderMinutesPerMonth":30,"maxAiTokensPerMonth":500000}'),
    ('GROWTH',       'Growth',       79.00,  '{"maxUsers":5,"maxDocuments":500,"maxMediaStorageGb":25,"maxGeneratedVideosPerMonth":50,"maxScheduledPostsPerMonth":200,"maxRenderMinutesPerMonth":120,"maxAiTokensPerMonth":2000000}'),
    ('BUSINESS',     'Business',     199.00, '{"maxUsers":10,"maxDocuments":1000,"maxMediaStorageGb":100,"maxGeneratedVideosPerMonth":100,"maxScheduledPostsPerMonth":500,"maxRenderMinutesPerMonth":600,"maxAiTokensPerMonth":10000000}'),
    ('ENTERPRISE',   'Enterprise',   NULL,   '{"maxUsers":null,"maxDocuments":null,"maxMediaStorageGb":null,"maxGeneratedVideosPerMonth":null,"maxScheduledPostsPerMonth":null,"maxRenderMinutesPerMonth":null,"maxAiTokensPerMonth":null}')
ON CONFLICT (code) DO NOTHING;

-- =========================================================
-- Seed plan features
-- =========================================================

INSERT INTO plan_features (plan_code, feature_code, enabled) VALUES
    -- STARTER plan
    ('STARTER', 'WHATSAPP_BOT',              true),
    ('STARTER', 'CRM_DASHBOARD',             true),
    ('STARTER', 'CAMPAIGNS',                 true),
    ('STARTER', 'DOCUMENT_CONTROL',          false),
    ('STARTER', 'ZERO_KNOWLEDGE_STORAGE',    false),
    ('STARTER', 'DOCUMENT_AI_ANALYZER',      false),
    ('STARTER', 'AI_TREND_PICKER',           false),
    ('STARTER', 'AI_CONTENT_GENERATOR',      false),
    ('STARTER', 'MEDIA_LIBRARY',             false),
    ('STARTER', 'VIDEO_TEMPLATE_ENGINE',     false),
    ('STARTER', 'SCHEDULED_PUBLISHING',      false),
    ('STARTER', 'INSTAGRAM_PUBLISHING',      false),
    ('STARTER', 'YOUTUBE_PUBLISHING',        false),
    ('STARTER', 'BYO_MEDIA_STORAGE',         false),
    ('STARTER', 'BYO_DOCUMENT_STORAGE',      false),
    ('STARTER', 'CUSTOMER_KMS',              false),

    -- GROWTH plan
    ('GROWTH',  'WHATSAPP_BOT',              true),
    ('GROWTH',  'CRM_DASHBOARD',             true),
    ('GROWTH',  'CAMPAIGNS',                 true),
    ('GROWTH',  'DOCUMENT_CONTROL',          true),
    ('GROWTH',  'ZERO_KNOWLEDGE_STORAGE',    false),
    ('GROWTH',  'DOCUMENT_AI_ANALYZER',      true),
    ('GROWTH',  'AI_TREND_PICKER',           true),
    ('GROWTH',  'AI_CONTENT_GENERATOR',      true),
    ('GROWTH',  'MEDIA_LIBRARY',             true),
    ('GROWTH',  'VIDEO_TEMPLATE_ENGINE',     false),
    ('GROWTH',  'SCHEDULED_PUBLISHING',      true),
    ('GROWTH',  'INSTAGRAM_PUBLISHING',      true),
    ('GROWTH',  'YOUTUBE_PUBLISHING',        false),
    ('GROWTH',  'BYO_MEDIA_STORAGE',         false),
    ('GROWTH',  'BYO_DOCUMENT_STORAGE',      false),
    ('GROWTH',  'CUSTOMER_KMS',              false),

    -- BUSINESS plan
    ('BUSINESS','WHATSAPP_BOT',              true),
    ('BUSINESS','CRM_DASHBOARD',             true),
    ('BUSINESS','CAMPAIGNS',                 true),
    ('BUSINESS','DOCUMENT_CONTROL',          true),
    ('BUSINESS','ZERO_KNOWLEDGE_STORAGE',    true),
    ('BUSINESS','DOCUMENT_AI_ANALYZER',      true),
    ('BUSINESS','AI_TREND_PICKER',           true),
    ('BUSINESS','AI_CONTENT_GENERATOR',      true),
    ('BUSINESS','MEDIA_LIBRARY',             true),
    ('BUSINESS','VIDEO_TEMPLATE_ENGINE',     true),
    ('BUSINESS','SCHEDULED_PUBLISHING',      true),
    ('BUSINESS','INSTAGRAM_PUBLISHING',      true),
    ('BUSINESS','YOUTUBE_PUBLISHING',        true),
    ('BUSINESS','BYO_MEDIA_STORAGE',         false),
    ('BUSINESS','BYO_DOCUMENT_STORAGE',      false),
    ('BUSINESS','CUSTOMER_KMS',              false),

    -- ENTERPRISE plan
    ('ENTERPRISE','WHATSAPP_BOT',            true),
    ('ENTERPRISE','CRM_DASHBOARD',           true),
    ('ENTERPRISE','CAMPAIGNS',               true),
    ('ENTERPRISE','DOCUMENT_CONTROL',        true),
    ('ENTERPRISE','ZERO_KNOWLEDGE_STORAGE',  true),
    ('ENTERPRISE','DOCUMENT_AI_ANALYZER',    true),
    ('ENTERPRISE','AI_TREND_PICKER',         true),
    ('ENTERPRISE','AI_CONTENT_GENERATOR',    true),
    ('ENTERPRISE','MEDIA_LIBRARY',           true),
    ('ENTERPRISE','VIDEO_TEMPLATE_ENGINE',   true),
    ('ENTERPRISE','SCHEDULED_PUBLISHING',    true),
    ('ENTERPRISE','INSTAGRAM_PUBLISHING',    true),
    ('ENTERPRISE','YOUTUBE_PUBLISHING',      true),
    ('ENTERPRISE','BYO_MEDIA_STORAGE',       true),
    ('ENTERPRISE','BYO_DOCUMENT_STORAGE',    true),
    ('ENTERPRISE','CUSTOMER_KMS',            true)
ON CONFLICT (plan_code, feature_code) DO NOTHING;

-- =========================================================
-- Seed default tenant subscription and feature flags
-- Assigns every existing tenant to BUSINESS plan (trial mode)
-- and enables all BUSINESS features for them
-- =========================================================

INSERT INTO tenant_subscriptions (tenant_id, plan_code, status, trial_ends_at)
SELECT id, 'BUSINESS', 'TRIAL', NOW() + INTERVAL '30 days'
FROM tenants
ON CONFLICT DO NOTHING;

INSERT INTO tenant_features (tenant_id, feature_code, enabled, enabled_at)
SELECT t.id, pf.feature_code, pf.enabled, CASE WHEN pf.enabled THEN NOW() ELSE NULL END
FROM tenants t
CROSS JOIN plan_features pf
WHERE pf.plan_code = 'BUSINESS'
ON CONFLICT (tenant_id, feature_code) DO NOTHING;
