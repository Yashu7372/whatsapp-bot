-- V13: media_assets for local file storage
CREATE TABLE media_assets (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    original_name VARCHAR(500)  NOT NULL,
    stored_path   VARCHAR(1000) NOT NULL,
    content_type  VARCHAR(255)  NOT NULL,
    size_bytes    BIGINT        NOT NULL DEFAULT 0,
    asset_type    VARCHAR(100)  NOT NULL DEFAULT 'DOCUMENT',
    ref_id        UUID,
    uploaded_by   UUID          REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_media_assets_tenant_id ON media_assets(tenant_id);
CREATE INDEX idx_media_assets_ref_id    ON media_assets(ref_id);
