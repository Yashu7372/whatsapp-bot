-- =========================================================
-- V26 - Gated, provider-neutral video generation workflow
-- =========================================================

CREATE TABLE video_generation_jobs (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    topic                    VARCHAR(1000) NOT NULL,
    mode                     VARCHAR(50)  NOT NULL DEFAULT 'FACELESS',
    platform                 VARCHAR(50)  NOT NULL DEFAULT 'INSTAGRAM',
    target_duration_seconds  INT          NOT NULL DEFAULT 30,
    state                    VARCHAR(50)  NOT NULL DEFAULT 'INTAKE',
    status                   VARCHAR(50)  NOT NULL DEFAULT 'READY',
    options                  JSONB        NOT NULL DEFAULT '{}',
    artifacts                JSONB        NOT NULL DEFAULT '[]',
    last_gate_message        TEXT,
    error_message            TEXT,
    created_at               TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_video_generation_jobs_tenant_status
    ON video_generation_jobs(tenant_id, status, created_at DESC);

CREATE INDEX idx_video_generation_jobs_tenant_state
    ON video_generation_jobs(tenant_id, state, created_at DESC);
