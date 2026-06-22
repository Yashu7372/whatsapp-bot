-- =========================================================
-- V7 - Publishing Foundation
-- =========================================================

CREATE TABLE publish_jobs (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    content_idea_id     UUID        NOT NULL REFERENCES content_ideas(id),
    platform_account_id UUID        NOT NULL REFERENCES platform_accounts(id),
    platform_code       VARCHAR(50) NOT NULL,
    status              VARCHAR(50) NOT NULL DEFAULT 'SCHEDULED',
    scheduled_at        TIMESTAMP   NOT NULL,
    content_snapshot    JSONB       NOT NULL DEFAULT '{}',
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_publish_jobs_tenant_status ON publish_jobs(tenant_id, status);
CREATE INDEX idx_publish_jobs_due           ON publish_jobs(status, scheduled_at)
    WHERE status = 'SCHEDULED';

CREATE TABLE publish_results (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    job_id              UUID        NOT NULL REFERENCES publish_jobs(id),
    success             BOOLEAN     NOT NULL,
    external_publish_id VARCHAR(300),
    error_message       TEXT,
    published_at        TIMESTAMP,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_publish_results_job ON publish_results(job_id);
