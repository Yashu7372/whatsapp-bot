CREATE TABLE reel_render_jobs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    video_script_id UUID NOT NULL REFERENCES video_scripts(id) ON DELETE CASCADE,
    template_code VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    voice VARCHAR(100),
    include_voice BOOLEAN NOT NULL DEFAULT FALSE,
    asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    asset_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    output_stored_path VARCHAR(1000),
    output_size_bytes BIGINT,
    error_message TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_reel_render_jobs_tenant_created
    ON reel_render_jobs (tenant_id, created_at DESC);

CREATE INDEX idx_reel_render_jobs_status_created
    ON reel_render_jobs (status, created_at ASC);
