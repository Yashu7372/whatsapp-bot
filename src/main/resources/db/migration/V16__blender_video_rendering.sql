ALTER TABLE video_scripts
    ADD COLUMN IF NOT EXISTS template_code VARCHAR(100) NOT NULL DEFAULT 'TALKING_PRESENTER';

CREATE TABLE IF NOT EXISTS video_render_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    video_script_id UUID NOT NULL REFERENCES video_scripts(id) ON DELETE CASCADE,
    template_code   VARCHAR(100) NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    scene_plan      JSONB NOT NULL,
    output_path     TEXT,
    log_path        TEXT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_video_render_jobs_tenant_script
    ON video_render_jobs(tenant_id, video_script_id, created_at DESC);
