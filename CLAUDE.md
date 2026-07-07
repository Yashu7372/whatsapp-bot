# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## What this is

The primary backend for a multi-tenant WhatsApp AI platform. Spring Boot 3.3.6 on Java 21 with virtual threads. Handles all WhatsApp webhook traffic, runs LangChain4j AI agents with tool use, manages a pgvector RAG knowledge base, and exposes the CRM API consumed by the React frontend.

This is a production system — treat every change as a senior engineer working on a product that others depend on.

---

## Stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21, virtual threads enabled |
| Framework | Spring Boot 3.3.6 |
| AI / LLM | LangChain4j 1.0.0, pluggable providers (Gemini, Ollama, OpenAI, Anthropic) |
| Embeddings | `all-MiniLM-L6-v2` (384-dim, local, free) |
| Vector search | pgvector on Postgres 16 |
| Schema migrations | Flyway (validate-only at boot — never `ddl-auto: create`) |
| WhatsApp | Meta Graph API v19.0 |
| Auth | Spring Security + JWT (JJWT 0.12.6) |
| Infra | GCP Cloud Run, Cloud SQL, Secret Manager, Cloud Build |
| Build | Maven 3.9, multi-stage Docker (temurin-21) |

---

## Commands

```bash
# Local development (Postgres must be running first)
docker compose up postgres          # from monorepo root
mvn spring-boot:run                 # starts on :8080

# Tests
mvn test                            # all tests
mvn test -Dtest=SomeClassTest       # single test class

# Build
mvn package -DskipTests             # build JAR
docker build -t whatsapp-bot .      # container image

# Deploy to GCP
gcloud builds submit --config=cloudbuild.yaml
```

---

## Package layout

```
src/main/java/com/whatsappbot/
├── api/                          REST controllers (thin — delegate to application layer)
│   ├── WebhookController         GET (Meta verification) + POST (enqueues to outbox)
│   ├── CrmConversationController /api/v1/crm/conversations — list, get, messages
│   ├── CrmContactController      /api/v1/crm/contacts
│   ├── CrmBookingController      /api/v1/crm/bookings (service appointments)
│   ├── DashboardStatsController  /api/v1/crm/stats
│   ├── OrderController           /api/v1/crm/orders
│   ├── ProductController         /api/v1/crm/products
│   ├── KnowledgeController       /api/v1/knowledge — ingest/search RAG docs
│   ├── LiveChatController        /api/v1/live-chat — intervene/assign/transfer/resolve
│   ├── WhatsAppTemplateController /api/v1/templates — CRUD + send approved templates
│   ├── WhatsappInteractiveController  /api/v1/interactive — buttons/lists/flows
│   ├── WebhookConfigController   /api/v1/webhook-config
│   └── PlatformAccountController /api/v1/platform-accounts
│
├── application/
│   ├── ai/TenantAiService        Builds system prompt, calls WhatsAppAgent, clears ThreadLocals
│   ├── automobile/AutomobileServiceTools   @Tool methods: vehicle lookup, service history, appointments
│   ├── context/TenantExecutionContext      ThreadLocal: tenant + contact + conversation per AI call
│   ├── conversation/ConversationService    Register inbound, save outbound, mark handoff
│   ├── interactive/
│   │   ├── WhatsappNativeInteractiveService   Sends buttons, lists, catalog, flows, location
│   │   └── WhatsappNativeInteractiveTools     @Tool facade for AI agent
│   ├── knowledge/KnowledgeService          Chunk → embed → store; search → RAG context
│   ├── livechat/LiveChatService            State machine transitions
│   ├── template/
│   │   ├── WhatsAppTemplateService         Template management + audit
│   │   └── WhatsAppTemplateToolService     @Tool facade for AI agent
│   ├── tenant/TenantService                Resolve tenant by Meta phone_number_id
│   └── webhook/
│       ├── WebhookApplicationService       Main inbound flow orchestrator
│       └── WhatsappInteractiveInboundHandler   Handles button/list/flow/order payloads
│
├── domain/                       Entities, repositories, enums only — no service logic
│   └── (tenant, contact, conversation, message, knowledge, template, interactive,
│        product, order, service, appointment, vehicle, agent)
│
└── infrastructure/
    ├── ai/
    │   ├── AiConfig              @Bean ChatModel + ChatMemoryProvider
    │   ├── ChatModelFactory      Builds provider-specific ChatModel from AiProperties
    │   ├── AiProperties          @ConfigurationProperties for ai.*
    │   ├── AiProvider            Enum: GEMINI | OLLAMA | OPENAI | ANTHROPIC
    │   ├── WhatsAppAgent         @AiService interface (4 tool beans wired)
    │   ├── EmbeddingConfig       @Bean AllMiniLmL6V2 EmbeddingModel
    │   └── PgVectorKnowledgeStore  384-dim HNSW cosine similarity search
    ├── tenant/TenantContext       ThreadLocal<UUID> tenantId
    └── whatsapp/
        ├── WhatsAppGraphClient          Send text messages
        ├── WhatsAppTemplateGraphClient  Send approved templates
        ├── WhatsappInteractiveGraphClient   Send interactive payloads
        ├── WhatsAppWebhookParser         Parse inbound webhook JSON
        └── WhatsAppInboundMessage        Record: waId, phone, displayName, text, type
```

---

## Inbound message flow (critical path)

```
Meta POST /webhook
  └── WebhookController.receiveMessage()          ← always returns 200 immediately
        └── webhookOutboxService.enqueue()         ← persists to outbox table
              └── WebhookOutboxProcessor (async)   ← polls every 1s, batch 5, 3 retries
                    └── WebhookApplicationService.handleIncomingWebhook()
                          1. WhatsAppWebhookParser.parseFirstMessage()
                          2. TenantService.resolveActiveTenant(phoneNumberId)
                          3. ConversationService.registerInboundMessage()
                             → upsert Contact, upsert Conversation, save Message
                             → returns null on duplicate (wa_message_id unique index)
                          4. WhatsappInteractiveInboundHandler.handleIfNativeInteractivePayload()
                             → handles order/cart/button/list/flow responses → stop if true
                          5. conversation.canBotReply() check
                             → false if status != ACTIVE or botEnabled == false → stop
                          6. Non-text message check → NON_TEXT_REPLY, markHumanRequested
                          7. TenantAiService.reply()
                             → TenantContext.setTenantId + TenantExecutionContext.set
                             → KnowledgeService.buildContext() (top-5 cosine RAG hits)
                             → WhatsAppTemplateService.describeAiEnabledTemplates()
                             → WhatsAppAgent.chat(conversationId, systemPrompt, waId, message)
                             → clears both ThreadLocals in finally
                          8. HUMAN_HANDOFF_REQUIRED check → markHumanRequested
                          9. WhatsAppGraphClient.sendTextMessage()
                         10. ConversationService.saveAiOutbound()
```

---

## AI layer

### WhatsAppAgent (4 tool beans)

```java
@AiService(tools = {"whatsAppTemplateTools", "whatsappNativeInteractiveTools",
                    "whatsappButtonReplyTools", "automobileServiceTools"})
public interface WhatsAppAgent {
    String chat(@MemoryId String conversationId,
                @V("systemPrompt") String systemPrompt,
                @V("customerWhatsappId") String customerWhatsappId,
                @UserMessage String userMessage);
}
```

| Bean | Class | What it does |
|---|---|---|
| `whatsAppTemplateTools` | `WhatsAppTemplateToolService` | List + send approved Meta templates |
| `whatsappNativeInteractiveTools` | `WhatsappNativeInteractiveTools` | Buttons, lists, catalog, flows, location |
| `whatsappButtonReplyTools` | (separate bean) | List registered quick-reply buttons |
| `automobileServiceTools` | `AutomobileServiceTools` | Customer lookup, vehicle history, appointments |

**Rules all tools follow:**
- Read tenant/contact identity from `TenantContext`/`TenantExecutionContext` — never from LLM input
- Any `phone` parameter that is null/blank resolves from context — LLM must never ask the customer for their own number
- Pass `null` for the phone parameter on every tool call; the tool resolves it

### System prompt construction

`TenantAiService.reply()` concatenates:
1. `tenant.getSystemPrompt()` — per-tenant custom instructions
2. Current date context (from tenant timezone)
3. `GLOBAL_GUARDRAILS` — static constant (grounding rules, tool-use rules, customer identity policy)
4. Approved template descriptions from `WhatsAppTemplateService`
5. RAG knowledge context — top-5 cosine similarity hits from pgvector

### Chat memory

`AiConfig` provides `ChatMemoryProvider` backed by `MessageWindowChatMemory` (configurable via `ai.memory.max-messages`, default 20). `WhatsAppAgent.chat()` takes `@MemoryId String conversationId`. Memory is in-JVM — wiped on restart, not shared across Cloud Run instances.

### AI provider switching

Change `AI_PROVIDER` env var and restart. No code change, no rebuild.

```
AI_PROVIDER=GEMINI     → ChatModelFactory builds GeminiChatModel (production default)
AI_PROVIDER=OLLAMA     → OllamaChatModel (local default in application-local.yml)
AI_PROVIDER=OPENAI     → OpenAiChatModel
AI_PROVIDER=ANTHROPIC  → AnthropicChatModel
```

---

## Conversation state machine

```
ACTIVE       ──(bot replies)──────────────────────────► ACTIVE
ACTIVE       ──(HUMAN_HANDOFF_REQUIRED or non-text)──► REQUESTING  (botEnabled=false)
REQUESTING   ──(agent.intervene())───────────────────► INTERVENE   (botEnabled=false, assignedAgentId set)
INTERVENE    ──(agent.resolve())─────────────────────► RESOLVED    (botEnabled=true)
RESOLVED     ──(agent.reopenForBot())────────────────► ACTIVE      (botEnabled=true, assignedAgentId cleared)
```

`ConversationEntity.canBotReply()` is true only when `status == ACTIVE && botEnabled == true`.

---

## Database schema

All changes go through Flyway migrations in `src/main/resources/db/migration/`. Never modify schema outside a new versioned migration. Never enable `ddl-auto: create`.

| Migration range | Domain |
|---|---|
| V1 | Core: tenants, contacts, conversations, messages, agents, knowledge, products, templates, interactive flows, orders |
| V2–V4 | Automobile: vehicles, service_records, service_appointments; SpeedWheels seed data |
| V5 | Webhook outbox (webhook_outbox table, status/retry/processing fields) |
| V6 | Platform foundation: workspaces, team, roles |
| V7–V21 | Campaign approval, trend intelligence, publishing, lead signals, analytics, auth users, media assets, documents, video scripts, SaaS foundation, object storage, zero-knowledge docs, jobs, video templates, seed admin users |

**Key tables:**
- `tenants` — `phone_number_id` (Meta), `system_prompt`, `business_type`, `access_token_encrypted`
- `contacts` — `(tenant_id, wa_id)` unique pair; `wa_id` = WhatsApp customer identifier
- `conversations` — `status`, `bot_enabled`, `assigned_agent_id`
- `messages` — `wa_message_id` unique partial index (dedup)
- `knowledge_embeddings` — 384-dim pgvector, HNSW cosine index
- `webhook_outbox` — stores pending inbound webhooks; processor polls and retries
- `service_appointments` — slot-based; status: AVAILABLE | BOOKED | COMPLETED | CANCELLED

---

## Tenant isolation

Every query touching tenant-scoped data **must include `tenant_id` in the WHERE clause**. Leaking tenant A's data to tenant B is a security incident.

Flow per request:
1. `TenantService.resolveActiveTenant(phoneNumberId)` — looks up `tenants` by Meta phone number ID
2. `TenantContext.setTenantId(tenant.getId())` — sets `ThreadLocal<UUID>`
3. `TenantExecutionContext.set(tenant, contact, conversation, phone)` — richer context for tool layer
4. Both cleared in `TenantAiService.reply()`'s `finally` block

CRM controllers read tenant from JWT claims (`@AuthenticationPrincipal Claims claims`), extract `tenantId`, and filter all queries by it.

---

## Authentication (CRM API)

All `/api/v1/crm/*` and `/api/v1/*` endpoints (except webhook) require a JWT Bearer token in the `Authorization` header. Spring Security validates the JWT; the `Claims` object is injected via `@AuthenticationPrincipal`. Controllers extract `tenantId` from claims to scope queries.

---

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL` | Yes | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | Yes | Database credentials |
| `WHATSAPP_VERIFY_TOKEN` | Yes | Meta webhook verification |
| `WHATSAPP_ACCESS_TOKEN` | Yes | Fallback Graph API bearer (per-tenant preferred) |
| `AI_PROVIDER` | Yes | `GEMINI` / `OLLAMA` / `OPENAI` / `ANTHROPIC` |
| `AI_GEMINI_API_KEY` | If Gemini | Google AI Studio key |
| `AI_GEMINI_MODEL` | No | Default: `gemini-2.5-flash` |
| `AI_OLLAMA_BASE_URL` | If Ollama | Default: `http://localhost:11434` |
| `AI_OPENAI_API_KEY` | If OpenAI | — |
| `AI_ANTHROPIC_API_KEY` | If Anthropic | — |
| `WHATSAPP_MOCK_SEND_ENABLED` | No | `true` = log instead of calling Meta (local dev) |
| `KNOWLEDGE_REBUILD_ON_STARTUP` | No | `true` = re-embed all docs at startup |
| `SPRING_PROFILES_ACTIVE` | No | `local` or `prod` |
| `GCP_PROJECT_ID` / `GCP_REGION` | If using GCP | — |

In production, all secrets come from Google Secret Manager via `cloudbuild.yaml`. `SPRING_PROFILES_ACTIVE=prod` activates `application-prod.yml` (Cloud SQL Socket Factory, HikariCP 2–10 pool).

---

## Non-negotiable rules

1. **Webhook boundary must not throw.** `WebhookController.receiveMessage()` always returns 2xx. Every downstream exception is caught and logged, never propagated.

2. **Multi-tenant isolation is sacred.** Every tenant-scoped query must include `tenant_id`. Every tool call reads identity from ThreadLocal context, never from LLM-supplied parameters.

3. **Schema changes go through Flyway only.** Write a new `V{n+1}__description.sql`. Never touch `ddl-auto`. Check existing migrations before writing entity or repository changes.

4. **No hardcoding.** All config values, model names, retry counts, timeouts, and business text come from environment variables or config — never Java literals.

5. **No per-tenant or per-business-type Java branches.** AI behavior is controlled via the system prompt and tool layer. No `if (businessType == AUTOMOBILE)` in service logic.

6. **Constructor injection only.** Use `@RequiredArgsConstructor`. No `@Autowired` field injection. No static mutable state beyond the existing ThreadLocal contexts.

---

## Definition of done for any change

- Compiles (`mvn package`) and passes all tests (`mvn test`)
- No new hardcoded config, no raw status strings where an enum exists
- No new code path that can throw past the webhook boundary unhandled
- Any tenant-scoped data access still filters by `tenant_id`
- If AI service or tool layer was touched: multi-turn memory and identity resolution still work
