-- =========================================================
-- V4 - WhatsApp quick-reply button registry
--
-- Generic, per-tenant registry of WhatsApp reply-button ids the AI is
-- allowed to offer via sendReplyButtonsToCustomer, and what happens when a
-- customer taps one. This replaces per-business hardcoded routing in
-- WhatsappInteractiveInboundHandler: onboarding a new tenant's buttons is a
-- data insert, not a Java code change or redeploy.
--
-- reply_kind is forward-looking:
--   TEXT      - send reply_text immediately (implemented now).
--   TOOL_CALL - reserved for a future where tapping the button invokes an
--               existing @Tool (e.g. book_service -> listAvailableSlots)
--               instead of/in addition to a canned reply. tool_name and
--               tool_arguments_json are nullable today and only read once
--               TOOL_CALL handling is implemented, so this migration adds
--               the columns now to avoid a second schema change later.
-- =========================================================

create table whatsapp_button_replies (
                                         id uuid primary key default gen_random_uuid(),

                                         tenant_id uuid not null references tenants(id),

    -- Stable id used as the WhatsApp interactive button/list "id" field.
    -- This is what the AI must use verbatim when offering this choice, and
    -- what WhatsappInteractiveInboundHandler looks up on inbound tap.
                                         button_id varchar(120) not null,

    -- Display title the AI should use on the button (<=20 chars is the
    -- WhatsApp Cloud API's own limit for reply button titles; not enforced
    -- here at the DB level, validated at send time).
                                         button_title varchar(60) not null,

                                         reply_kind varchar(20) not null default 'TEXT',

                                         reply_text text,

    -- Reserved for TOOL_CALL kind (see header comment). Not read by current
    -- code; present so a future migration isn't needed to add tool routing.
                                         tool_name varchar(120),
                                         tool_arguments_json text,

    -- Lets a tenant order buttons consistently when several are offered
    -- together, and lets the AI-facing listing show them in a sane order.
                                         sort_order int not null default 0,

                                         description text,

                                         active boolean not null default true,

                                         created_at timestamp not null default now(),
                                         updated_at timestamp not null default now(),

                                         constraint uk_whatsapp_button_replies unique (tenant_id, button_id),
                                         constraint chk_whatsapp_button_replies_kind check (reply_kind in ('TEXT', 'TOOL_CALL')),
                                         constraint chk_whatsapp_button_replies_text_present
                                             check (reply_kind <> 'TEXT' or (reply_text is not null and length(trim(reply_text)) > 0))
);

create index idx_whatsapp_button_replies_tenant_active
    on whatsapp_button_replies(tenant_id, active);

-- =========================================================
-- Seed: SpeedWheels (AUTOMOBILE) quick-reply buttons
-- Matches the two buttons already in use in this tenant's conversations
-- today (book_service, ask_question), now driven by data instead of a
-- hardcoded switch statement.
-- =========================================================

insert into whatsapp_button_replies (tenant_id, button_id, button_title, reply_kind, reply_text, sort_order, description)
select t.id, b.button_id, b.button_title, 'TEXT', b.reply_text, b.sort_order, b.description
from tenants t
         cross join (values
                         (
                             'book_service',
                             'Book Service',
                             'Sure — let''s get your service booked. Which service do you need (e.g. oil change, brake check, full inspection), and what date works best for you?',
                             1,
                             'Customer wants to book an automobile service appointment.'
                         ),
                         (
                             'ask_question',
                             'Ask a Question',
                             'What would you like to know? You can ask about hours, pricing, or services.',
                             2,
                             'Customer has a general question, not yet specified.'
                         )
) as b(button_id, button_title, reply_text, sort_order, description)
where t.tenant_code = 'speedwheels'
    on conflict (tenant_id, button_id) do update
                                              set button_title = excluded.button_title,
                                              reply_text = excluded.reply_text,
                                              sort_order = excluded.sort_order,
                                              description = excluded.description,
                                              active = true,
                                              updated_at = now();