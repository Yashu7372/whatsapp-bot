CREATE TABLE IF NOT EXISTS character_profiles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    name            VARCHAR(150) NOT NULL,
    description     TEXT,
    visual_style    VARCHAR(300),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_character_profiles_tenant
    ON character_profiles(tenant_id, active, created_at DESC);

CREATE TABLE IF NOT EXISTS storyboard_image_jobs (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id),
    video_script_id      UUID NOT NULL REFERENCES video_scripts(id) ON DELETE CASCADE,
    character_profile_id UUID REFERENCES character_profiles(id),
    output_asset_id      UUID REFERENCES media_assets(id) ON DELETE SET NULL,
    shot_index           INT NOT NULL,
    prompt               TEXT NOT NULL,
    quality_mode         VARCHAR(30) NOT NULL,
    provider             VARCHAR(50) NOT NULL DEFAULT 'ROUTER',
    status               VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    estimated_cost_usd   NUMERIC(10, 4) NOT NULL DEFAULT 0,
    actual_cost_usd      NUMERIC(10, 4) NOT NULL DEFAULT 0,
    error_message        TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_storyboard_image_jobs_tenant_script
    ON storyboard_image_jobs(tenant_id, video_script_id, created_at DESC);
