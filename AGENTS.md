# AGENTS.md
*Guidance for AI coding agents working in the WhatsApp Bot Phase 1 codebase*

## Project Overview
Multi-tenant WhatsApp AI agent platform running on Spring Boot 3.3.6 with Java 21. Handles incoming WhatsApp webhooks, routes to tenant-specific AI models (Google Gemini), and sends responses back via Meta's Graph API.

**Key fact**: One running application serves multiple WhatsApp Business Numbers (different tenants).

## Critical Architecture Pattern: Multi-Tenancy

All tenants share one database but operate in isolation. Request isolation happens via:

1. **Tenant Resolution**: Every webhook carries `entry[0].changes[0].value.metadata.phone_number_id` → matched to `tenants.phone_number_id` in the database
2. **ThreadLocal Context**: `TenantContext` holds current tenant UUID for the request lifecycle
   - Always set in request path: `TenantContext.setTenantId(tenant.getId())` before AI operations 
   - Always clear after: `TenantContext.clear()` (use finally block)
   - Critical for query filtering and AI isolation
3. **Query Filtering**: Every domain query is scoped by tenant (database-level via foreign keys)

**Example pattern (from TenantAiService.java)**:
```java
try {
    TenantContext.setTenantId(tenant.getId());
    return whatsAppAgent.chat(systemPrompt, contactWaId, userMessage);
} finally {
    TenantContext.clear();
}
```

**When adding queries**: Always check `findByTenant*` repository methods exist. Never write tenant-unaware queries.

## Request Flow & Service Boundaries

Incoming webhook → Response sent:
```
Meta Webhook
  ↓
WebhookController (HTTP entry, verifies token)
  ↓
WebhookApplicationService.handleIncomingWebhook()
  ├─ WhatsAppWebhookParser.parseFirstMessage() → WhatsAppInboundMessage DTO
  ├─ TenantService.resolveActiveTenant(phoneNumberId)
  ├─ ConversationService.registerInboundMessage() → creates/updates Contact, Conversation, Message
  ├─ WhatsappInteractiveInboundHandler (handles button/list/flow replies)
  ├─ TenantAiService.reply() (AI chain with LangChain4j)
  │  ├─ KnowledgeService.buildContext() (Phase 2: RAG)
  │  ├─ WhatsAppTemplateService.describeAiEnabledTemplates()
  │  └─ WhatsAppAgent.chat() (LangChain4j @AiService)
  └─ WhatsAppGraphClient.sendTextMessage() (Meta Graph v19.0 API)
```

**Key:** All I/O errors in webhook handler are caught and logged (Meta expects 200 OK immediately to avoid retry spam).

## Database Schema & Entities

**Multi-tenant core**:
- `tenants` (uuid id, tenant_code, phone_number_id, system_prompt, access_token_encrypted)
- `contacts` (tenant_id FK, wa_id, phone_number, display_name)
- `conversations` (tenant_id FK, contact_id FK, status ENUM, bot_enabled, last_message_at)
- `messages` (tenant_id FK, conversation_id FK, message_type ENUM, text_body, raw_payload)

**Conversation statuses**: ACTIVE, REQUESTING (human handoff), INTERVENE, RESOLVED

**Phase 2 additions** (already in schema but not used yet):
- `knowledge_documents`, `knowledge_embeddings` (pgvector)

Flyway migration: `V1__phase1_multi_tenant_inbox.sql` (~741 lines, includes phase 2/3 schema)

## Conventions & Patterns

### Layering
- **`api/`**: REST controllers, @RestController, path routing, request DTOs
- **`application/`**: Business logic services, @Service, transactional boundaries, orchestration
- **`domain/`**: JPA entities, repositories, business rules (canBotReply(), requestHuman(), etc.)
- **`infrastructure/`**: External clients (WhatsApp, Gemini), utilities, TenantContext

### Service Naming & Dependency Injection
- Service classes end in `Service` (@Service, @RequiredArgsConstructor)
- Repositories: `XyzRepository extends JpaRepository<Entity, UUID>`
- Always inject via constructor (Lombok @RequiredArgsConstructor)
- Thread-safe: services are singleton beans

### Entity Patterns
- All use `uuid id` as primary key
- All have `created_at` and `updated_at` with @PrePersist/@PreUpdate hooks
- Foreign keys use `@ManyToOne(fetch = FetchType.LAZY)` to avoid N+1
- Business logic lives as public methods on entity (e.g., `conversation.canBotReply()`)

### Logging
- All classes use `@Slf4j` (Lombok annotation)
- Webhook errors logged but not re-thrown (quick 200 OK to Meta)
- Info level for happy path, warn/error for failures
- Include tenant_code, conversation id, message type for traceability

### Configuration
- Environment variables in `application.yml` (all required at startup)
- Profile-specific: `application-local.yml` (mock enabled, debug), `application-prod.yml` (secrets via Cloud Run)
- LangChain4j Spring Boot starter auto-configures Gemini if GEMINI_API_KEY set

## LangChain4j & AI Integration

**WhatsAppAgent interface** (`infrastructure/ai/WhatsAppAgent.java`):
- `@AiService` interface with tools: `whatsAppTemplateTools`, `whatsappNativeInteractiveTools`
- `String chat(systemPrompt, customerWhatsappId, userMessage)`
- SystemMessage template injected at runtime

**System prompt construction** (TenantAiService):
```
tenant.getSystemPrompt()
  + GLOBAL_GUARDRAILS (hardcoded platform rules)
  + template context (approved WhatsApp templates)
  + knowledge base context (Phase 2: RAG from pgvector)
```

**Guardrails**: Platform-wide rules like "never invent prices", "reply HUMAN_HANDOFF_REQUIRED for escalation"

## Virtual Threads (Java 21)

- Enabled in `application.yml`: `spring.threads.virtual.enabled: true`
- In Dockerfile: `-XX:+UseContainerSupport` and 75% RAM allocation for Cloud Run
- Useful for high-concurrency webhook processing
- No explicit thread pool code needed; Spring handles transparently

## Running Locally

```bash
# 1. Start PostgreSQL (docker-compose.yml)
docker compose up -d

# 2. Set environment variables
export GEMINI_API_KEY="your-key"
export WHATSAPP_VERIFY_TOKEN="your-verify-token"
export WHATSAPP_ACCESS_TOKEN="your-cloud-api-token"
export DB_URL="jdbc:postgresql://localhost:5432/whatsapp_bot"
export DB_USERNAME="whatsapp_bot"
export DB_PASSWORD="whatsapp_bot"
export GCP_PROJECT_ID="whatsapp-bot-yash-2025"  # Updated Sept 2025
export GCP_REGION="me-central1"

# 3. Start app (Maven Wrapper)
./mvnw spring-boot:run
   OR
mvn spring-boot:run

# 4. Update sample tenant in DB (before testing with Meta)
UPDATE tenants SET phone_number_id='YOUR_PHONE_ID', waba_id='YOUR_WABA_ID'
  WHERE tenant_code='localbites';
```

**Local profile**: Mock sends enabled (`WHATSAPP_MOCK_SEND_ENABLED=true`), no actual Meta calls. Debug logging on `com.whatsappbot`.

## Building & Deployment

**Local JAR build**:
```bash
mvn package  # Creates target/whatsapp-bot-phase1-0.0.1-SNAPSHOT.jar
```

**Docker image**:
```bash
docker build -t whatsapp-bot:latest .
```
Two-stage build: Maven build stage, then JRE 21 runtime with non-root `appuser`

**GCP Cloud Run** (via Cloud Build):
- Commits trigger `cloudbuild.yaml`
- Builds Docker image, pushes to Artifact Registry
- Deploys to Cloud Run (me-central1, 1 vCPU, 1GB RAM, autoscale 0–5 instances)
- Secrets injected: DB_URL, WHATSAPP_VERIFY_TOKEN, WHATSAPP_ACCESS_TOKEN, GEMINI_API_KEY
- Cloud SQL connection via Unix socket (no VPC needed)

## Adding New Features

**New AI Tool**:
1. Implement tool method in infrastructure package (e.g., `whatsappNativeInteractiveTools`)
2. Register in `@AiService(tools = {...})`
3. Add to system prompt description in WhatsAppAgent.java

**New tenant-aware query**:
1. Add repository method with `findByTenant...()` pattern
2. Ensure tenant foreign key in query
3. Test with ConversationService pattern

**New webhook message type**:
1. Extend `WhatsAppWebhookParser` to detect new type
2. Add to `WhatsAppInboundMessage` DTO
3. Add handler in `WebhookApplicationService`

## Key Files Reference

| File | Purpose |
|------|---------|
| `src/main/java/com/whatsappbot/WhatsappBotApplication.java` | Spring Boot entry |
| `src/main/java/com/whatsappbot/api/WebhookController.java` | Meta webhook HTTP endpoint |
| `src/main/java/com/whatsappbot/application/webhook/WebhookApplicationService.java` | Core request orchestration |
| `src/main/java/com/whatsappbot/infrastructure/tenant/TenantContext.java` | ThreadLocal tenant isolation |
| `src/main/java/com/whatsappbot/infrastructure/ai/WhatsAppAgent.java` | LangChain4j AI interface |
| `src/main/resources/db/migration/V1__phase1_multi_tenant_inbox.sql` | Full database schema |
| `Dockerfile` | Cloud Run deployment |
| `pom.xml` | Maven dependencies & build config |

## What's **Not** in Phase 1 (Yet)

- pgvector / semantic search (Phase 2)
- Products, catalogs, cart, orders (Phase 2)
- WhatsApp Flows (Phase 3)
- Agent dashboard login/RBAC (Phase 3)
- Template media sending (Phase 3)
- In-app chat management UI

Phase 2/3 schema already scaffolded in Flyway migration (empty tables ready).

## Common Pitfalls

1. **Forgetting TenantContext** → AI queries run without tenant filter → data leak across tenants
2. **Lazy-loading N+1** → Use `@ManyToOne(fetch = FetchType.LAZY)` but test conversation hydration carefully
3. **Swallowing exceptions in webhook** → Meta retries on non-200. We catch, log, and return 200. Don't re-throw.
4. **Access token in logs** → Fallback token used locally; production uses Cloud Run Secrets
5. **MOCK_SEND mode off locally** → Actual Meta calls attempted; set to `true` in local profile

