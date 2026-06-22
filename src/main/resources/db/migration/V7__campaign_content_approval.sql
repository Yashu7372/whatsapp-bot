-- =========================================================
-- V5 - Campaign, Content & Approval Foundation
-- =========================================================

CREATE TABLE campaigns (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id),
    name         VARCHAR(200) NOT NULL,
    goal         VARCHAR(50)  NOT NULL,
    status       VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    platform_codes TEXT[]     NOT NULL DEFAULT '{}',
    brief        TEXT,
    start_date   DATE,
    end_date     DATE,
    metadata     JSONB        NOT NULL DEFAULT '{}',
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_campaigns_tenant_status ON campaigns(tenant_id, status);

CREATE TABLE content_ideas (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id),
    campaign_id   UUID        REFERENCES campaigns(id),
    platform_code VARCHAR(50) NOT NULL,
    content_type  VARCHAR(50) NOT NULL,
    status        VARCHAR(50) NOT NULL DEFAULT 'GENERATED',
    topic         TEXT        NOT NULL,
    generated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    created_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_content_ideas_tenant_status ON content_ideas(tenant_id, status);
CREATE INDEX idx_content_ideas_campaign      ON content_ideas(campaign_id);

CREATE TABLE content_variants (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    content_idea_id  UUID        NOT NULL REFERENCES content_ideas(id),
    body             TEXT        NOT NULL,
    hashtags         TEXT[]      NOT NULL DEFAULT '{}',
    call_to_action   VARCHAR(500),
    version          INT         NOT NULL DEFAULT 1,
    created_at       TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_content_variants_idea ON content_variants(content_idea_id);

CREATE TABLE approval_tasks (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL REFERENCES tenants(id),
    content_idea_id  UUID        NOT NULL REFERENCES content_ideas(id),
    status           VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    reviewer_note    TEXT,
    reviewed_by      VARCHAR(200),
    reviewed_at      TIMESTAMP,
    created_at       TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_approval_tasks_tenant_status ON approval_tasks(tenant_id, status);
CREATE INDEX idx_approval_tasks_idea          ON approval_tasks(content_idea_id);
