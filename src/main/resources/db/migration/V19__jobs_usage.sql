-- =========================================================
-- V19 - Background Jobs and Usage Tracking
-- =========================================================

-- Generic background job queue (MVP: DB-backed polling)
CREATE TABLE background_jobs (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         REFERENCES tenants(id) ON DELETE CASCADE,
    job_type      VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL DEFAULT '{}',
    status        VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    priority      INT          NOT NULL DEFAULT 5,
    run_after     TIMESTAMP    NOT NULL DEFAULT NOW(),
    retry_count   INT          NOT NULL DEFAULT 0,
    max_retries   INT          NOT NULL DEFAULT 3,
    locked_by     VARCHAR(200),
    locked_at     TIMESTAMP,
    error_message TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bg_jobs_pending    ON background_jobs(status, priority DESC, run_after)
    WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX idx_bg_jobs_tenant     ON background_jobs(tenant_id, job_type);
CREATE INDEX idx_bg_jobs_locked     ON background_jobs(locked_by) WHERE locked_by IS NOT NULL;

-- Daily usage aggregates for plan-limit enforcement
CREATE TABLE tenant_usage_daily (
    id                     UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID    NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    usage_date             DATE    NOT NULL,
    storage_bytes          BIGINT  NOT NULL DEFAULT 0,
    bandwidth_bytes        BIGINT  NOT NULL DEFAULT 0,
    ai_tokens              BIGINT  NOT NULL DEFAULT 0,
    render_seconds         BIGINT  NOT NULL DEFAULT 0,
    generated_assets_count INT     NOT NULL DEFAULT 0,
    scheduled_posts_count  INT     NOT NULL DEFAULT 0,
    published_posts_count  INT     NOT NULL DEFAULT 0,
    document_count         INT     NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, usage_date)
);

CREATE INDEX idx_tenant_usage_tenant_date ON tenant_usage_daily(tenant_id, usage_date DESC);
