-- =========================================================
-- V4 - Platform Foundation
-- Introduces platform registry and per-tenant platform accounts.
-- WhatsApp-specific fields remain on tenants table for backward
-- compatibility — they are migrated to platform_accounts gradually.
-- =========================================================

create table platforms (
    code         varchar(50)  primary key,
    display_name varchar(100) not null,
    enabled      boolean      not null default true,
    capability_json jsonb     not null default '{}',
    created_at   timestamp    not null default now()
);

create table platform_accounts (
    id                  uuid         primary key default gen_random_uuid(),
    tenant_id           uuid         not null references tenants(id),
    platform_code       varchar(50)  not null references platforms(code),
    external_account_id varchar(200),
    account_name        varchar(200),
    account_handle      varchar(200),
    credential_ref      varchar(300),
    metadata            jsonb        not null default '{}',
    active              boolean      not null default true,
    created_at          timestamp    not null default now(),
    updated_at          timestamp    not null default now(),
    unique (tenant_id, platform_code, external_account_id)
);

create index idx_platform_accounts_tenant_platform
    on platform_accounts(tenant_id, platform_code, active);

-- =========================================================
-- Seed supported platforms
-- Only WHATSAPP and MANUAL_IMPORT are enabled at launch.
-- Others are seeded disabled and enabled when real adapters ship.
-- =========================================================

insert into platforms (code, display_name, enabled) values
    ('WHATSAPP',        'WhatsApp',                 true),
    ('INSTAGRAM',       'Instagram',                false),
    ('FACEBOOK',        'Facebook',                 false),
    ('TIKTOK',          'TikTok',                   false),
    ('YOUTUBE',         'YouTube',                  false),
    ('LINKEDIN',        'LinkedIn',                 false),
    ('PINTEREST',       'Pinterest',                false),
    ('GOOGLE_BUSINESS', 'Google Business Profile',  false),
    ('X_TWITTER',       'X / Twitter',              false),
    ('REDDIT',          'Reddit',                   false),
    ('WEBSITE',         'Website',                  false),
    ('MANUAL_IMPORT',   'Manual Import',            true)
on conflict (code) do nothing;
