-- V12: tenant_users for JWT authentication
CREATE TABLE tenant_users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(200),
    role          VARCHAR(50)  NOT NULL DEFAULT 'ADMIN',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_tenant_users_email UNIQUE (email)
);

CREATE INDEX idx_tenant_users_tenant_id ON tenant_users(tenant_id);
CREATE INDEX idx_tenant_users_email     ON tenant_users(email);

-- Default admin user for the demo tenant (password: admin123 — bcrypt)
INSERT INTO tenant_users (tenant_id, email, password_hash, full_name, role)
SELECT id,
       'admin@demo.com',
       '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY.5AEfAxwd6O3.',
       'Demo Admin',
       'ADMIN'
FROM tenants
WHERE tenant_code = 'DEMO'
ON CONFLICT DO NOTHING;
