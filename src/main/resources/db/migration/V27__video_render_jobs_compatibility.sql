-- =========================================================
-- V27 - Compatibility table for the existing video RenderJobEntity
--
-- V20 contains an older content-item render_jobs queue. The newer reel worker
-- maps a separate entity to video_render_jobs. Keep both models isolated rather
-- than changing the meaning of the older table.
-- =========================================================

CREATE TABLE IF NOT EXISTS video_render_jobs (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    script_id        UUID         NOT NULL REFERENCES video_scripts(id) ON DELETE CASCADE,
    template_code    VARCHAR(100) NOT NULL,
    asset_ids        JSONB        NOT NULL DEFAULT '[]',
    asset_urls       JSONB        NOT NULL DEFAULT '[]',
    voice            VARCHAR(100) NOT NULL DEFAULT 'af_heart',
    brand_name       VARCHAR(200),
    call_to_action   VARCHAR(300),
    status           VARCHAR(50)  NOT NULL DEFAULT 'QUEUED',
    progress         INT          NOT NULL DEFAULT 0,
    output_path      VARCHAR(1000),
    error_message    TEXT,
    retry_count      INT          NOT NULL DEFAULT 0,
    max_retries      INT          NOT NULL DEFAULT 3,
    next_attempt_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    started_at       TIMESTAMP,
    completed_at     TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_video_render_jobs_tenant_created
    ON video_render_jobs(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_video_render_jobs_queue
    ON video_render_jobs(status, next_attempt_at, created_at)
    WHERE status = 'QUEUED';
