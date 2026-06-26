-- =========================================================
-- V20 - Video Templates, Render Jobs, Social Accounts,
--        Trend Items, and Content Items
-- =========================================================

-- Global and per-tenant trend discovery index
CREATE TABLE trend_items (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    source_platform  VARCHAR(100) NOT NULL,
    niche            VARCHAR(200),
    country          VARCHAR(100),
    language         VARCHAR(50),
    title            VARCHAR(500),
    trend_url        TEXT,
    audio_ref        TEXT,
    hashtags         TEXT[]       NOT NULL DEFAULT '{}',
    score            NUMERIC(10, 2) NOT NULL DEFAULT 0,
    metadata         JSONB        NOT NULL DEFAULT '{}',
    discovered_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trend_items_platform_score ON trend_items(source_platform, score DESC);
CREATE INDEX idx_trend_items_niche          ON trend_items(niche, score DESC);

-- Tenant-saved trend bookmarks
CREATE TABLE tenant_saved_trends (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    trend_id   UUID        NOT NULL REFERENCES trend_items(id) ON DELETE CASCADE,
    saved_by   UUID        REFERENCES tenant_users(id) ON DELETE SET NULL,
    status     VARCHAR(50) NOT NULL DEFAULT 'SAVED',
    notes      TEXT,
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, trend_id)
);

CREATE INDEX idx_saved_trends_tenant ON tenant_saved_trends(tenant_id, status);

-- Comprehensive content lifecycle items (replaces/extends content_ideas for full pipeline)
CREATE TABLE content_items (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title                   VARCHAR(500),
    niche                   VARCHAR(200),
    platform                VARCHAR(100),
    content_type            VARCHAR(100),
    source_trend_id         UUID        REFERENCES trend_items(id) ON DELETE SET NULL,
    caption                 TEXT,
    hashtags                TEXT[]      NOT NULL DEFAULT '{}',
    script_text             TEXT,
    generation_instructions JSONB,
    template_id             UUID,
    final_asset_id          UUID        REFERENCES media_assets(id) ON DELETE SET NULL,
    status                  VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    scheduled_at            TIMESTAMP,
    created_by              UUID        REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at              TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_content_items_tenant_status ON content_items(tenant_id, status);
CREATE INDEX idx_content_items_tenant_sched  ON content_items(tenant_id, scheduled_at)
    WHERE scheduled_at IS NOT NULL;

-- Video templates stored in object storage
CREATE TABLE video_templates (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        REFERENCES tenants(id) ON DELETE CASCADE,
    scope             VARCHAR(50) NOT NULL DEFAULT 'SYSTEM',
    name              VARCHAR(300) NOT NULL,
    category          VARCHAR(200),
    format            VARCHAR(50) NOT NULL,
    template_asset_id UUID        REFERENCES media_assets(id) ON DELETE SET NULL,
    preview_asset_id  UUID        REFERENCES media_assets(id) ON DELETE SET NULL,
    thumbnail_asset_id UUID       REFERENCES media_assets(id) ON DELETE SET NULL,
    config            JSONB       NOT NULL DEFAULT '{}',
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_video_templates_scope     ON video_templates(scope, active);
CREATE INDEX idx_video_templates_tenant    ON video_templates(tenant_id, active);
CREATE INDEX idx_video_templates_format    ON video_templates(format, scope, active);

-- Render job queue (worker picks up from here)
CREATE TABLE render_jobs (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    content_item_id     UUID        NOT NULL REFERENCES content_items(id) ON DELETE CASCADE,
    template_id         UUID        REFERENCES video_templates(id) ON DELETE SET NULL,
    status              VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    render_instructions JSONB       NOT NULL DEFAULT '{}',
    output_asset_id     UUID        REFERENCES media_assets(id) ON DELETE SET NULL,
    error_message       TEXT,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_render_jobs_tenant_status ON render_jobs(tenant_id, status);
CREATE INDEX idx_render_jobs_pending       ON render_jobs(status, created_at)
    WHERE status IN ('PENDING', 'RUNNING');

-- Social media publishing accounts (tokens encrypted at rest)
CREATE TABLE social_accounts (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    platform                VARCHAR(100) NOT NULL,
    account_name            VARCHAR(300),
    external_account_id     VARCHAR(300),
    access_token_encrypted  TEXT,
    refresh_token_encrypted TEXT,
    token_expires_at        TIMESTAMP,
    status                  VARCHAR(50) NOT NULL DEFAULT 'CONNECTED',
    metadata                JSONB       NOT NULL DEFAULT '{}',
    created_at              TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_social_accounts_tenant     ON social_accounts(tenant_id, platform, status);

-- Scheduled publishing jobs (worker picks up from here)
CREATE TABLE publishing_jobs (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    content_item_id   UUID        NOT NULL REFERENCES content_items(id) ON DELETE CASCADE,
    social_account_id UUID        NOT NULL REFERENCES social_accounts(id) ON DELETE CASCADE,
    platform          VARCHAR(100) NOT NULL,
    asset_id          UUID        REFERENCES media_assets(id) ON DELETE SET NULL,
    caption           TEXT,
    hashtags          TEXT[]      NOT NULL DEFAULT '{}',
    scheduled_at      TIMESTAMP   NOT NULL,
    status            VARCHAR(50) NOT NULL DEFAULT 'SCHEDULED',
    external_post_id  VARCHAR(300),
    external_post_url TEXT,
    retry_count       INT         NOT NULL DEFAULT 0,
    error_message     TEXT,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_publishing_jobs_tenant_status ON publishing_jobs(tenant_id, status);
CREATE INDEX idx_publishing_jobs_due           ON publishing_jobs(status, scheduled_at)
    WHERE status IN ('SCHEDULED', 'RETRYING');

-- Seed sample system video templates
INSERT INTO video_templates (scope, name, category, format, config) VALUES
    ('SYSTEM', 'Clean Reel - Minimal',  'general',  'REEL_9_16',    '{"style":"minimal","pace":"medium","colors":["#ffffff","#000000"]}'),
    ('SYSTEM', 'Product Showcase',      'product',  'REEL_9_16',    '{"style":"corporate","pace":"slow","colors":["#0055cc","#ffffff"]}'),
    ('SYSTEM', 'YouTube Intro Standard','general',  'YOUTUBE_16_9', '{"style":"engaging","pace":"fast","colors":["#ff0000","#ffffff"]}'),
    ('SYSTEM', 'Story - Vibrant',       'lifestyle','STORY_9_16',   '{"style":"vibrant","pace":"fast","colors":["#ff6b6b","#ffd93d"]}'),
    ('SYSTEM', 'Shorts - Trending',     'general',  'SHORTS_9_16',  '{"style":"trendy","pace":"fast","colors":["#6c5ce7","#00cec9"]}')
ON CONFLICT DO NOTHING;
