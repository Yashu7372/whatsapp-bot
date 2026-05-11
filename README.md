# WhatsApp Bot Phase 1 - Multi-Tenant Foundation

This zip converts the basic hardcoded WhatsApp bot into a Phase 1 multi-tenant foundation.

## What is included

- Single `/webhook` endpoint for Meta WhatsApp callbacks
- Tenant resolution using `metadata.phone_number_id`
- PostgreSQL schema through Flyway
- Tenant, contact, conversation, and message persistence
- Conversation state foundation:
  - `ACTIVE`
  - `REQUESTING`
  - `INTERVENE`
  - `RESOLVED`
- Tenant-specific system prompt injection into LangChain4j
- Gemini 1.5 Flash retained for the free API based MVP
- Tenant-aware WhatsApp Graph API sending
- Virtual threads enabled for Java 21

## What is intentionally not included in Phase 1

- pgvector/RAG storage
- products/catalogs/orders
- WhatsApp Flows
- agent dashboard APIs
- media/template sending
- RBAC and login

Those belong to Phase 2 and Phase 3.

## Run locally

Start PostgreSQL:

```bash
docker compose up -d
```

Set environment variables:

```bash
export GEMINI_API_KEY="your-gemini-api-key"
export WHATSAPP_VERIFY_TOKEN="your-meta-webhook-verify-token"
export WHATSAPP_ACCESS_TOKEN="your-meta-cloud-api-token"
```

Start the app:

```bash
./mvnw spring-boot:run
```

If you do not have Maven Wrapper files, run:

```bash
mvn spring-boot:run
```

## Important first database update

Flyway inserts a sample tenant with a placeholder phone number ID.

Update it before testing with Meta:

```sql
update tenants
set phone_number_id = 'YOUR_META_PHONE_NUMBER_ID',
    waba_id = 'YOUR_WABA_ID',
    access_token_encrypted = null
where tenant_code = 'localbites';
```

For local MVP, the app uses `WHATSAPP_ACCESS_TOKEN` when `access_token_encrypted` is null.

Before production, replace this with proper encryption or Vault/KMS.

## Meta webhook verification

Configure Meta webhook callback URL:

```text
https://your-domain.com/webhook
```

Verify token must match:

```yaml
whatsapp.verify-token
```

## Tenant routing logic

Incoming webhook payload is parsed from:

```text
entry[0].changes[0].value.metadata.phone_number_id
```

That value is matched against:

```sql
tenants.phone_number_id
```

This is how one Spring Boot application can serve many WhatsApp Business numbers.

## Current message flow

```text
Meta WhatsApp webhook
  -> WebhookController
  -> WebhookApplicationService
  -> WhatsAppWebhookParser
  -> TenantService
  -> ConversationService
  -> TenantAiService
  -> WhatsAppGraphClient
```

## Next recommended Phase 2

Add:

- `knowledge_documents`
- `knowledge_embeddings`
- PostgreSQL `pgvector`
- tenant-filtered retrieval
- admin endpoint for ingesting business knowledge
