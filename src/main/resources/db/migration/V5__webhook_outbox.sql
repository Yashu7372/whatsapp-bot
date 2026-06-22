create table webhook_outbox (
    id           uuid primary key default gen_random_uuid(),
    payload      jsonb        not null,
    status       varchar(20)  not null default 'PENDING',
    retry_count  int          not null default 0,
    error_message text,
    created_at   timestamp    not null default now(),
    processed_at timestamp
);

-- Partial index: only PENDING rows are queried by the processor.
create index idx_webhook_outbox_pending
    on webhook_outbox(created_at)
    where status = 'PENDING';

-- Recovery query uses this to find stuck PROCESSING rows.
create index idx_webhook_outbox_processing
    on webhook_outbox(created_at)
    where status = 'PROCESSING';
