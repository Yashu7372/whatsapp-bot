-- =========================================================
-- V6 - Trend Intelligence
-- =========================================================

CREATE TABLE trend_sources (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenants(id),
    name        VARCHAR(200) NOT NULL,
    source_type VARCHAR(50)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    metadata    JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trend_sources_tenant ON trend_sources(tenant_id, active);

CREATE TABLE trend_signals (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenants(id),
    source_id         UUID          REFERENCES trend_sources(id),
    keyword           VARCHAR(200),
    hashtag           VARCHAR(200),
    topic             TEXT,
    country           VARCHAR(10),
    industry          VARCHAR(100),
    platform_code     VARCHAR(50),
    raw_score         DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    final_score       DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    freshness_score   DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    growth_score      DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    relevance_score   DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    engagement_score  DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    brand_safety_score DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    captured_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trend_signals_tenant_score    ON trend_signals(tenant_id, final_score DESC);
CREATE INDEX idx_trend_signals_tenant_captured ON trend_signals(tenant_id, captured_at DESC);
