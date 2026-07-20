CREATE TABLE IF NOT EXISTS video_templates (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID REFERENCES tenants(id),
    code        VARCHAR(100) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    definition  JSONB NOT NULL DEFAULT '{}',
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_video_templates_global_code
    ON video_templates(code) WHERE tenant_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_video_templates_tenant_code
    ON video_templates(tenant_id, code) WHERE tenant_id IS NOT NULL;

INSERT INTO video_templates (id, tenant_id, code, name, description, definition)
VALUES
    ('10000000-0000-0000-0000-000000000001', NULL, 'PRODUCT_HOOK_V1', 'Product Hook',
     'Fast hook, product benefit and call-to-action template.',
     '{"width":1080,"height":1920,"fps":30,"sceneCount":4,"safeArea":80,"textStyle":"bold","recommendedDurationSecs":30}'::jsonb),
    ('10000000-0000-0000-0000-000000000002', NULL, 'LISTICLE_V1', 'Listicle',
     'Numbered tips or feature list with punchy captions.',
     '{"width":1080,"height":1920,"fps":30,"sceneCount":5,"safeArea":80,"textStyle":"numbered","recommendedDurationSecs":35}'::jsonb),
    ('10000000-0000-0000-0000-000000000003', NULL, 'MINIMAL_QUOTE_V1', 'Minimal Story',
     'Clean story or quote layout for educational and brand content.',
     '{"width":1080,"height":1920,"fps":30,"sceneCount":3,"safeArea":100,"textStyle":"minimal","recommendedDurationSecs":20}'::jsonb)
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS video_render_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    script_id       UUID NOT NULL REFERENCES video_scripts(id),
    template_code   VARCHAR(100) NOT NULL,
    asset_ids       JSONB NOT NULL DEFAULT '[]',
    asset_urls      JSONB NOT NULL DEFAULT '[]',
    voice           VARCHAR(100) NOT NULL DEFAULT 'af_heart',
    brand_name      VARCHAR(200),
    call_to_action  VARCHAR(300),
    status          VARCHAR(50) NOT NULL DEFAULT 'QUEUED',
    progress        INT NOT NULL DEFAULT 0,
    output_path     VARCHAR(1000),
    error_message   TEXT,
    retry_count     INT NOT NULL DEFAULT 0,
    max_retries     INT NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_video_render_jobs_tenant_created
    ON video_render_jobs(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_video_render_jobs_worker
    ON video_render_jobs(status, next_attempt_at, created_at);
