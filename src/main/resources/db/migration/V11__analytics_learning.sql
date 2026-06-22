-- =========================================================
-- V9 - Analytics & Learning
-- =========================================================

CREATE TABLE analytics_snapshots (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    publish_job_id  UUID        REFERENCES publish_jobs(id),
    platform_code   VARCHAR(50) NOT NULL,
    views           BIGINT      NOT NULL DEFAULT 0,
    likes           BIGINT      NOT NULL DEFAULT 0,
    comments        BIGINT      NOT NULL DEFAULT 0,
    shares          BIGINT      NOT NULL DEFAULT 0,
    clicks          BIGINT      NOT NULL DEFAULT 0,
    leads           BIGINT      NOT NULL DEFAULT 0,
    captured_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_analytics_snapshots_tenant  ON analytics_snapshots(tenant_id, captured_at DESC);
CREATE INDEX idx_analytics_snapshots_job     ON analytics_snapshots(publish_job_id);

CREATE TABLE learning_insights (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenants(id),
    insight_type     VARCHAR(100)  NOT NULL,
    title            VARCHAR(300)  NOT NULL,
    summary          TEXT          NOT NULL,
    recommendation   TEXT,
    confidence_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    supporting_data  JSONB         NOT NULL DEFAULT '{}',
    generated_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_learning_insights_tenant ON learning_insights(tenant_id, generated_at DESC);
