-- =========================================================
-- WhatsApp AI Platform - Full Initial Schema
-- Includes:
-- Phase 1: Multi-tenant inbox
-- Phase 2: Knowledge + pgvector RAG
-- Phase 3: Live chat + approved templates
-- Phase 3B: Native WhatsApp interactive messages, catalog, cart, flows
-- =========================================================

create extension if not exists pgcrypto;
create extension if not exists vector;

-- =========================================================
-- 1. TENANTS
-- =========================================================

create table tenants (
                         id uuid primary key default gen_random_uuid(),

                         tenant_code varchar(100) not null unique,
                         business_name varchar(200) not null,
                         business_type varchar(50) not null,

                         phone_number_id varchar(100) not null unique,
                         waba_id varchar(100),
                         access_token_encrypted text,

                         system_prompt text not null,
                         default_language varchar(20) not null default 'en',
                         timezone varchar(100) not null default 'Asia/Dubai',

                         active boolean not null default true,
                         created_at timestamp not null default now(),
                         updated_at timestamp not null default now()
);

create index idx_tenants_phone_number_id
    on tenants(phone_number_id);

-- =========================================================
-- 2. CONTACTS
-- =========================================================

create table contacts (
                          id uuid primary key default gen_random_uuid(),

                          tenant_id uuid not null references tenants(id),
                          wa_id varchar(50) not null,
                          phone_number varchar(50) not null,
                          display_name varchar(200),

                          last_seen_at timestamp,

                          created_at timestamp not null default now(),
                          updated_at timestamp not null default now(),

                          constraint uk_contacts_tenant_wa_id unique (tenant_id, wa_id)
);

create index idx_contacts_tenant_wa_id
    on contacts(tenant_id, wa_id);

-- =========================================================
-- 3. CONVERSATIONS
-- =========================================================

create table conversations (
                               id uuid primary key default gen_random_uuid(),

                               tenant_id uuid not null references tenants(id),
                               contact_id uuid not null references contacts(id),

                               status varchar(50) not null default 'ACTIVE',
                               assigned_agent_id uuid,
                               bot_enabled boolean not null default true,
                               priority varchar(30) not null default 'NORMAL',

                               last_message_at timestamp,

                               created_at timestamp not null default now(),
                               updated_at timestamp not null default now(),

                               constraint uk_conversations_tenant_contact unique (tenant_id, contact_id)
);

create index idx_conversations_tenant_status
    on conversations(tenant_id, status);

create index idx_conversations_last_message_at
    on conversations(last_message_at desc);

-- =========================================================
-- 4. MESSAGES
-- =========================================================

create table messages (
                          id uuid primary key default gen_random_uuid(),

                          tenant_id uuid not null references tenants(id),
                          conversation_id uuid not null references conversations(id),

                          wa_message_id varchar(200),
                          direction varchar(20) not null,
                          message_type varchar(50) not null,

                          text_body text,
                          raw_payload text,

                          ai_generated boolean not null default false,
                          sent_by_agent_id uuid,

                          created_at timestamp not null default now()
);

create index idx_messages_conversation_created_at
    on messages(conversation_id, created_at desc);

create index idx_messages_tenant_created_at
    on messages(tenant_id, created_at desc);

-- =========================================================
-- 5. LIVE CHAT AGENTS
-- =========================================================

create table tenant_agents (
                               id uuid primary key default gen_random_uuid(),

                               tenant_id uuid not null references tenants(id),

                               name varchar(150) not null,
                               email varchar(200) not null,
                               role varchar(50) not null default 'AGENT',

                               active boolean not null default true,

                               created_at timestamp not null default now(),
                               updated_at timestamp not null default now(),

                               constraint uk_tenant_agents_email unique (tenant_id, email)
);

create index idx_tenant_agents_tenant_active
    on tenant_agents(tenant_id, active);

create table tenant_notification_contacts (
                                              id uuid primary key default gen_random_uuid(),

                                              tenant_id uuid not null references tenants(id),

                                              purpose varchar(100) not null,
                                              display_name varchar(150) not null,
                                              phone_number varchar(50) not null,

                                              active boolean not null default true,

                                              created_at timestamp not null default now(),
                                              updated_at timestamp not null default now(),

                                              constraint uk_tenant_notification_contacts unique (tenant_id, purpose, phone_number)
);

create index idx_tenant_notification_contacts_tenant_purpose
    on tenant_notification_contacts(tenant_id, purpose, active);

create table conversation_events (
                                     id uuid primary key default gen_random_uuid(),

                                     tenant_id uuid not null references tenants(id),
                                     conversation_id uuid not null references conversations(id),

                                     event_type varchar(100) not null,
                                     from_agent_id uuid,
                                     to_agent_id uuid,

                                     notes text,

                                     created_at timestamp not null default now()
);

create index idx_conversation_events_conversation
    on conversation_events(conversation_id, created_at desc);

create index idx_conversation_events_tenant
    on conversation_events(tenant_id, created_at desc);

-- =========================================================
-- 6. KNOWLEDGE DOCUMENTS + PGVECTOR
-- =========================================================

create table knowledge_documents (
                                     id uuid primary key default gen_random_uuid(),

                                     tenant_id uuid not null references tenants(id),

                                     title varchar(300) not null,
                                     document_type varchar(100) not null,
                                     source_type varchar(100) not null,

                                     content text not null,
                                     metadata jsonb,

                                     active boolean not null default true,

                                     created_at timestamp not null default now(),
                                     updated_at timestamp not null default now()
);

create index idx_knowledge_documents_tenant_active
    on knowledge_documents(tenant_id, active);

create table knowledge_embeddings (
                                      id uuid primary key default gen_random_uuid(),

                                      tenant_id uuid not null references tenants(id),
                                      document_id uuid not null references knowledge_documents(id) on delete cascade,

                                      chunk_index int not null,
                                      content text not null,

    -- AllMiniLmL6V2EmbeddingModel produces 384-dimensional vectors.
                                      embedding vector(384) not null,
                                      metadata jsonb,

                                      created_at timestamp not null default now(),

                                      constraint uk_knowledge_embeddings_document_chunk unique (document_id, chunk_index)
);

create index idx_knowledge_embeddings_tenant
    on knowledge_embeddings(tenant_id);

create index idx_knowledge_embeddings_embedding_hnsw
    on knowledge_embeddings
    using hnsw (embedding vector_cosine_ops);

-- =========================================================
-- 7. PRODUCT CATALOG
-- =========================================================

create table product_categories (
                                    id uuid primary key default gen_random_uuid(),

                                    tenant_id uuid not null references tenants(id),

                                    code varchar(100) not null,
                                    name varchar(200) not null,

                                    sort_order int default 0,

    -- Optional self-reference if you keep parent/child categories.
                                    category_id uuid references product_categories(id),

    -- Optional WhatsApp catalog/category metadata.
                                    retailer_id varchar(200),
                                    whatsapp_catalog_id varchar(200),
                                    inventory_count int,
                                    metadata jsonb,

                                    active boolean not null default true,

                                    created_at timestamp not null default now(),
                                    updated_at timestamp not null default now(),

                                    constraint uk_product_categories_tenant_code unique (tenant_id, code)
);

create index idx_product_categories_tenant_active
    on product_categories(tenant_id, active);

create table products (
                          id uuid primary key default gen_random_uuid(),

                          tenant_id uuid not null references tenants(id),
                          category_id uuid references product_categories(id),

                          name varchar(200) not null,
                          description text,

                          price numeric(12,2),
                          currency varchar(10) default 'AED',

    -- Must match Meta Commerce Manager product_retailer_id.
                          retailer_id varchar(200),

    -- WhatsApp catalog ID from Meta Commerce Manager.
                          whatsapp_catalog_id varchar(200),

                          image_url text,
                          inventory_count int,
                          metadata jsonb,

                          active boolean not null default true,

                          created_at timestamp not null default now(),
                          updated_at timestamp not null default now(),

                          constraint uk_products_tenant_retailer_id unique (tenant_id, retailer_id)
);

create index idx_products_tenant_active
    on products(tenant_id, active);

create index idx_products_tenant_category_active
    on products(tenant_id, category_id, active);

-- =========================================================
-- 8. APPROVED WHATSAPP MESSAGE TEMPLATES
-- =========================================================

create table whatsapp_message_templates (
                                            id uuid primary key default gen_random_uuid(),

                                            tenant_id uuid not null references tenants(id),

                                            template_code varchar(120) not null,
                                            meta_template_name varchar(250) not null,
                                            language_code varchar(20) not null default 'en',

                                            category varchar(50) not null,
                                            audience varchar(50) not null default 'CUSTOMER',

                                            description text,
                                            body_preview text,

    -- JSON text describing variables, expected components, examples, and rules.
                                            component_schema_json text,

    -- Optional default Graph API components JSON array.
                                            default_components_json text,

                                            enabled_for_ai boolean not null default false,
                                            active boolean not null default true,

                                            created_at timestamp not null default now(),
                                            updated_at timestamp not null default now(),

                                            constraint uk_whatsapp_message_templates unique (tenant_id, template_code, language_code)
);

create index idx_whatsapp_message_templates_ai
    on whatsapp_message_templates(tenant_id, enabled_for_ai, active);

create table whatsapp_template_send_audit (
                                              id uuid primary key default gen_random_uuid(),

                                              tenant_id uuid not null references tenants(id),
                                              conversation_id uuid references conversations(id),
                                              template_id uuid not null references whatsapp_message_templates(id),

                                              recipient_type varchar(50) not null,
                                              recipient_phone_number varchar(50) not null,

                                              request_components_json text,
                                              graph_payload_json text,
                                              graph_status_code int,
                                              graph_response_body text,

                                              sent_by varchar(50) not null default 'AI_TOOL',

                                              created_at timestamp not null default now()
);

create index idx_whatsapp_template_send_audit_conversation
    on whatsapp_template_send_audit(conversation_id, created_at desc);

create index idx_whatsapp_template_send_audit_tenant
    on whatsapp_template_send_audit(tenant_id, created_at desc);

-- =========================================================
-- 9. CANNED RESPONSES
-- =========================================================

create table canned_responses (
                                  id uuid primary key default gen_random_uuid(),

                                  tenant_id uuid not null references tenants(id),

                                  shortcut varchar(100) not null,
                                  title varchar(200) not null,
                                  body text not null,

                                  active boolean not null default true,

                                  created_at timestamp not null default now(),
                                  updated_at timestamp not null default now(),

                                  constraint uk_canned_responses_shortcut unique (tenant_id, shortcut)
);

create index idx_canned_responses_tenant_active
    on canned_responses(tenant_id, active);

-- =========================================================
-- 10. NATIVE WHATSAPP INTERACTIVE MESSAGES
-- Buttons, lists, catalog, product cards, flows, location request
-- =========================================================

create table whatsapp_interactive_messages (
                                               id uuid primary key default gen_random_uuid(),

                                               tenant_id uuid not null references tenants(id),
                                               conversation_id uuid references conversations(id),
                                               contact_id uuid references contacts(id),

                                               direction varchar(20) not null default 'OUTBOUND',
                                               interactive_type varchar(80) not null,

                                               provider_message_id varchar(200),

                                               request_payload jsonb not null,
                                               response_payload jsonb,

                                               status varchar(40) not null default 'SENT',
                                               error_message text,

                                               created_at timestamp not null default now()
);

create index idx_whatsapp_interactive_tenant_conversation
    on whatsapp_interactive_messages(tenant_id, conversation_id, created_at desc);

create index idx_whatsapp_interactive_contact
    on whatsapp_interactive_messages(contact_id, created_at desc);

-- =========================================================
-- 11. WHATSAPP FLOWS
-- =========================================================

create table whatsapp_flow_registry (
                                        id uuid primary key default gen_random_uuid(),

                                        tenant_id uuid not null references tenants(id),

                                        flow_key varchar(120) not null,
                                        flow_id varchar(120) not null,
                                        flow_cta varchar(80) not null default 'Open',
                                        screen_id varchar(120),

                                        description text,

                                        active boolean not null default true,

                                        created_at timestamp not null default now(),
                                        updated_at timestamp not null default now(),

                                        constraint uk_whatsapp_flow_registry unique (tenant_id, flow_key)
);

create index idx_whatsapp_flow_registry_tenant_active
    on whatsapp_flow_registry(tenant_id, active);

create table whatsapp_flow_submissions (
                                           id uuid primary key default gen_random_uuid(),

                                           tenant_id uuid not null references tenants(id),
                                           conversation_id uuid references conversations(id),
                                           contact_id uuid references contacts(id),

                                           flow_id varchar(120),
                                           flow_token varchar(200),

                                           response_json jsonb not null,
                                           raw_payload jsonb not null,

                                           created_at timestamp not null default now()
);

create index idx_whatsapp_flow_submissions_tenant
    on whatsapp_flow_submissions(tenant_id, created_at desc);

create index idx_whatsapp_flow_submissions_conversation
    on whatsapp_flow_submissions(conversation_id, created_at desc);

-- =========================================================
-- 12. WHATSAPP CART / ORDERS
-- =========================================================

create table whatsapp_orders (
                                 id uuid primary key default gen_random_uuid(),

                                 tenant_id uuid not null references tenants(id),
                                 conversation_id uuid references conversations(id),
                                 contact_id uuid references contacts(id),

                                 wa_message_id varchar(200),
                                 catalog_id varchar(200),

                                 order_payload jsonb not null,
                                 status varchar(50) not null default 'RECEIVED',

                                 created_at timestamp not null default now(),
                                 updated_at timestamp not null default now()
);

create index idx_whatsapp_orders_tenant_contact
    on whatsapp_orders(tenant_id, contact_id, created_at desc);

create index idx_whatsapp_orders_conversation
    on whatsapp_orders(conversation_id, created_at desc);

create table whatsapp_order_items (
                                      id uuid primary key default gen_random_uuid(),

                                      whatsapp_order_id uuid not null references whatsapp_orders(id) on delete cascade,

                                      product_retailer_id varchar(200),
                                      product_id uuid references products(id),

                                      quantity int not null,
                                      item_price numeric(12,2),
                                      currency varchar(10),

                                      raw_item jsonb,

                                      created_at timestamp not null default now()
);

create index idx_whatsapp_order_items_order
    on whatsapp_order_items(whatsapp_order_id);

-- =========================================================
-- 13. SEED DATA - LOCALBITES TENANT
-- =========================================================

insert into tenants (
    tenant_code,
    business_name,
    business_type,
    phone_number_id,
    waba_id,
    access_token_encrypted,
    system_prompt
) values (
             'localbites',
             'LocalBites Restaurant',
             'RESTAURANT',
             'REPLACE_WITH_META_PHONE_NUMBER_ID',
             'REPLACE_WITH_WABA_ID',
             null,
             'You are a helpful WhatsApp customer service assistant for LocalBites Restaurant. Keep replies short, polite, and suitable for WhatsApp. Do not invent prices, menu items, offers, or policies. If the customer asks for a human agent, reply exactly HUMAN_HANDOFF_REQUIRED.'
         )
    on conflict (tenant_code) do nothing;

-- =========================================================
-- 14. SEED DATA - KNOWLEDGE
-- =========================================================

insert into knowledge_documents (
    tenant_id,
    title,
    document_type,
    source_type,
    content,
    metadata
)
select
    id,
    'LocalBites starter menu and FAQ',
    'MENU',
    'SEED',
    'LocalBites serves burgers, pizza, pasta, salads, desserts, and soft drinks. Opening hours are 10 AM to 11 PM daily. Delivery is available within 8 km. Average delivery time is 30 to 45 minutes. If the customer asks for unavailable items or exact stock, ask the team to confirm.',
    '{"seed": true}'::jsonb
from tenants
where tenant_code = 'localbites'
    on conflict do nothing;

-- =========================================================
-- 15. SEED DATA - DEMO TEMPLATE REGISTRY
-- These must match approved Meta template names before real sending.
-- =========================================================

insert into whatsapp_message_templates (
    tenant_id,
    template_code,
    meta_template_name,
    language_code,
    category,
    audience,
    description,
    body_preview,
    component_schema_json,
    enabled_for_ai
)
select
    id,
    'customer_welcome',
    'customer_welcome',
    'en',
    'UTILITY',
    'CUSTOMER',
    'Welcome or re-engagement template for a customer.',
    'Hi {{1}}, welcome to {{2}}. How can we help you today?',
    '{"body":["customer_name","business_name"],"buttons":[],"allowedUse":"customer welcome and re-engagement"}',
    true
from tenants
where tenant_code = 'localbites'
    on conflict do nothing;

insert into whatsapp_message_templates (
    tenant_id,
    template_code,
    meta_template_name,
    language_code,
    category,
    audience,
    description,
    body_preview,
    component_schema_json,
    enabled_for_ai
)
select
    id,
    'order_status_update',
    'order_status_update',
    'en',
    'UTILITY',
    'CUSTOMER',
    'Order status update to a customer.',
    'Hi {{1}}, your order {{2}} is now {{3}}.',
    '{"body":["customer_name","order_number","status"],"allowedUse":"order status updates only"}',
    true
from tenants
where tenant_code = 'localbites'
    on conflict do nothing;

insert into whatsapp_message_templates (
    tenant_id,
    template_code,
    meta_template_name,
    language_code,
    category,
    audience,
    description,
    body_preview,
    component_schema_json,
    enabled_for_ai
)
select
    id,
    'business_new_lead_alert',
    'business_new_lead_alert',
    'en',
    'UTILITY',
    'BUSINESS_CONTACT',
    'Internal business alert when a customer asks for human help or creates a lead.',
    'New WhatsApp lead: {{1}} - {{2}}',
    '{"body":["customer_phone","summary"],"allowedUse":"notify business staff only"}',
    true
from tenants
where tenant_code = 'localbites'
    on conflict do nothing;

-- =========================================================
-- 16. SEED DATA - PRODUCT CATEGORY + SAMPLE PRODUCTS
-- Replace retailer_id and catalog_id with real Meta Commerce Manager values.
-- =========================================================

insert into product_categories (
    tenant_id,
    code,
    name,
    sort_order,
    active
)
select
    id,
    'main_menu',
    'Main Menu',
    1,
    true
from tenants
where tenant_code = 'localbites'
    on conflict do nothing;

insert into products (
    tenant_id,
    category_id,
    name,
    description,
    price,
    currency,
    retailer_id,
    whatsapp_catalog_id,
    image_url,
    inventory_count,
    metadata,
    active
)
select
    t.id,
    c.id,
    'Classic Burger',
    'Classic beef burger with fries.',
    28.00,
    'AED',
    'localbites_burger_001',
    'REPLACE_WITH_META_CATALOG_ID',
    null,
    100,
    '{"seed": true}'::jsonb,
    true
from tenants t
         join product_categories c
              on c.tenant_id = t.id
                  and c.code = 'main_menu'
where t.tenant_code = 'localbites'
    on conflict do nothing;

insert into products (
    tenant_id,
    category_id,
    name,
    description,
    price,
    currency,
    retailer_id,
    whatsapp_catalog_id,
    image_url,
    inventory_count,
    metadata,
    active
)
select
    t.id,
    c.id,
    'Margherita Pizza',
    'Cheese and tomato pizza.',
    32.00,
    'AED',
    'localbites_pizza_001',
    'REPLACE_WITH_META_CATALOG_ID',
    null,
    100,
    '{"seed": true}'::jsonb,
    true
from tenants t
         join product_categories c
              on c.tenant_id = t.id
                  and c.code = 'main_menu'
where t.tenant_code = 'localbites'
    on conflict do nothing;