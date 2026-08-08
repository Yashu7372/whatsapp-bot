# whatsapp-bot — Project Context for Claude Code

This file is read automatically at the start of every session. It is the
source of truth for how to work in this codebase. Skills under
`.claude/skills/` go deeper on specific topics — always read the relevant
skill before touching that area of the code.

---

## What this is

A multi-tenant SaaS platform: businesses (tenants) deploy AI-powered
WhatsApp bots for their own end customers. Paying customers, real money,
real production traffic. Treat every change as a senior engineer working on
a product others depend on — not a script, not a prototype.

---

## Stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 (virtual threads enabled) |
| Framework | Spring Boot 3.3.6 |
| AI / LLM | LangChain4j 1.0.0, Vertex AI Gemini (`gemini-3.5-flash`) |
| Embeddings | `all-MiniLM-L6-v2` (384-dim, English-only, local) |
| Vector search | pgvector on Cloud SQL Postgres 15 |
| Schema migrations | Flyway (validate-only at boot, never `ddl-auto: create`) |
| Messaging | WhatsApp Business Cloud API (Meta Graph API v19.0) |
| Infra | GCP Cloud Run (`me-central1`), Cloud SQL, Secret Manager, Cloud Build |
| Build | Maven 3.9, multi-stage Docker (temurin-21) |

---

## Package layout

```
src/main/java/com/whatsappbot/
├── api/                        REST controllers (thin — delegate to application layer)
│   ├── WebhookController       GET (Meta verification) + POST (inbound messages)
│   ├── KnowledgeController     Ingest/search knowledge documents per tenant
│   ├── LiveChatController      Agent takeover/assign/transfer/resolve
│   ├── WhatsAppTemplateController  CRUD + send for approved templates
│   └── WhatsappInteractiveController  Send native interactive messages
│
├── application/                Use-case services (orchestrate domain + infrastructure)
│   ├── ai/
│   │   └── TenantAiService     Builds system prompt, calls WhatsAppAgent, returns reply
│   ├── automobile/
│   │   └── AutomobileServiceTools   @Tool methods for vehicle/appointment domain
│   ├── context/
│   │   └── TenantExecutionContext   ThreadLocal carrying tenant+contact+conversation per AI call
│   ├── conversation/
│   │   └── ConversationService  Register inbound, save outbound, mark handoff
│   ├── interactive/
│   │   ├── WhatsappNativeInteractiveService   Sends buttons/lists/catalog/flows/location
│   │   └── WhatsappNativeInteractiveTools     @Tool facade over the service for the AI agent
│   ├── knowledge/
│   │   ├── KnowledgeService    Ingest docs → chunk → embed; search → RAG context
│   │   ├── KnowledgeEmbeddingStartupRunner    Optional re-embed on startup
│   │   └── TextChunker         Splits document content into embeddable chunks
│   ├── livechat/
│   │   └── LiveChatService     intervene/assign/transfer/resolve lifecycle
│   ├── template/
│   │   ├── WhatsAppTemplateService     Low-level template send + audit
│   │   └── WhatsAppTemplateToolService @Tool facade exposing templates to the AI agent
│   ├── tenant/
│   │   └── TenantService       Resolve active tenant by Meta phone_number_id
│   └── webhook/
│       ├── WebhookApplicationService   Main inbound flow orchestrator
│       └── WhatsappInteractiveInboundHandler  Handles cart/order/list/button/flow payloads
│
├── domain/                     Entities, repositories, and enums — no service logic
│   ├── agent/          TenantAgent, TenantNotificationContact, AgentRole
│   ├── appointment/    ServiceAppointmentEntity (AVAILABLE/BOOKED/COMPLETED/CANCELLED)
│   ├── contact/        ContactEntity (one per tenant+wa_id pair)
│   ├── conversation/   ConversationEntity, ConversationStatus, ConversationPriority
│   ├── interactive/    WhatsappFlowRegistryEntity, InteractiveType
│   ├── knowledge/      KnowledgeDocument, DocumentType, SourceType
│   ├── message/        Message, MessageType, MessageDirection
│   ├── order/          WhatsappOrderEntity
│   ├── product/        ProductEntity, ProductCategoryEntity
│   ├── service/        ServiceRecordEntity
│   ├── template/       WhatsAppTemplateSendAudit, TemplateSentBy
│   ├── tenant/         TenantEntity, BusinessType, TenantRepository
│   └── vehicle/        VehicleEntity
│
└── infrastructure/             Technical adapters (AI, WhatsApp, tenant resolution)
    ├── ai/
    │   ├── AiConfig            @Bean ChatModel (Gemini) + ChatMemoryProvider
    │   ├── EmbeddingConfig     @Bean EmbeddingModel (AllMiniLmL6V2)
    │   ├── PgVectorKnowledgeStore   Embedding storage + HNSW cosine similarity search
    │   └── WhatsAppAgent       LangChain4j @AiService interface (3 tool beans wired)
    ├── tenant/
    │   └── TenantContext       ThreadLocal<UUID> holding current tenantId
    └── whatsapp/
        ├── WhatsAppGraphClient          Sends text messages via Meta Graph API
        ├── WhatsAppTemplateGraphClient  Sends approved templates
        ├── WhatsappInteractiveGraphClient   Sends interactive message payloads
        ├── WhatsAppTemplatePayloadBuilder   Builds Graph API components JSON
        ├── WhatsAppWebhookParser         Extracts first message from webhook JSON
        └── WhatsAppInboundMessage        Record: waId, phone, displayName, text, type, etc.
```

---

## Inbound message flow (the critical path)

```
Meta POST /webhook
  └── WebhookController.receiveMessage()          always returns 200
        └── WebhookApplicationService.handleIncomingWebhook()
              └── WhatsAppWebhookParser.parseFirstMessage()
                    └── handleMessage(inbound)
                          1. TenantService.resolveActiveTenant(phoneNumberId)
                          2. ConversationService.registerInboundMessage()
                             → upsert Contact, upsert Conversation, save Message
                             → returns null on duplicate (wa_message_id unique index)
                          3. WhatsappInteractiveInboundHandler.handleIfNativeInteractivePayload()
                             → handles order/cart/button/list/flow responses; returns true → stop
                          4. conversation.canBotReply() check
                             → false if status != ACTIVE or botEnabled == false → stop
                          5. Non-text message check → NON_TEXT_REPLY, markHumanRequested
                          6. TenantAiService.reply()
                             → sets TenantContext + TenantExecutionContext
                             → KnowledgeService.buildContext() for RAG
                             → WhatsAppTemplateService.describeAiEnabledTemplates()
                             → WhatsAppAgent.chat(conversationId, systemPrompt, waId, message)
                             → clears both ThreadLocals in finally
                          7. HUMAN_HANDOFF_REQUIRED check → markHumanRequested
                          8. WhatsAppGraphClient.sendTextMessage()
                          9. ConversationService.saveAiOutbound()
```

---

## Conversation state machine

```
ACTIVE  ──(bot replies)──> ACTIVE
ACTIVE  ──(HUMAN_HANDOFF_REQUIRED or non-text)──> REQUESTING  (botEnabled=false)
REQUESTING  ──(agent.intervene())──> INTERVENE  (botEnabled=false, assignedAgentId set)
INTERVENE  ──(agent.resolve())──> RESOLVED  (botEnabled=true)
RESOLVED  ──(agent.reopenForBot())──> ACTIVE  (botEnabled=true, assignedAgentId cleared)
```

`ConversationEntity.canBotReply()` returns true only when `status == ACTIVE && botEnabled == true`.

---

## AI layer — how the three tool beans wire together

`WhatsAppAgent` (`@AiService`) declares three tool beans:

| Bean name | Class | Purpose |
|---|---|---|
| `whatsAppTemplateTools` | `WhatsAppTemplateToolService` | List + send approved Meta templates to customers and business contacts |
| `whatsappNativeInteractiveTools` | `WhatsappNativeInteractiveTools` | Send buttons, lists, catalog, flows, location requests |
| `automobileServiceTools` | `AutomobileServiceTools` | Customer lookup, vehicle history, appointment slots, book, cancel |

All three read identity from `TenantContext`/`TenantExecutionContext` — they
never accept raw tenant IDs from the LLM. Every `@Tool` method that accepts a
`phone` parameter falls back to the context contact when the argument is
null/blank; the LLM must **never** ask the customer for their own number.

**Chat memory:** `AiConfig` exposes a `ChatMemoryProvider` bean
(`MessageWindowChatMemory`, max configurable via `ai.memory.max-messages`,
default 20). `WhatsAppAgent.chat(...)` takes `@MemoryId String conversationId`;
`TenantAiService` passes `conversation.getId().toString()` as that ID.
Memory is in-JVM — wiped on restart/redeploy, not shared across Cloud Run
instances. This is a known limitation; the correct fix when needed is a
`ChatMemoryStore` backed by the `MESSAGES` table.

---

## System prompt construction

`TenantAiService.reply(...)` builds the prompt by concatenation today:

```
tenant.getSystemPrompt()
  + GLOBAL_GUARDRAILS (static constant in TenantAiService)
  + approved template descriptions (WhatsAppTemplateService)
  + RAG knowledge context (KnowledgeService, top-5 cosine hits)
```

This is a known tech debt — see Task 3 in `IMPLEMENTATION_TASKS.md`. The
intended direction is a single versioned template resource
(`src/main/resources/prompts/system-template.md`) rendered by a shared
`PromptTemplateRenderer`, with no per-tenant or per-vertical Java branches.

---

## Database schema summary

All schema changes live in `src/main/resources/db/migration/`.
**Never** modify the schema outside of a new versioned Flyway migration.

| Migration | Description |
|---|---|
| `V1__phase1_multi_tenant_inbox.sql` | Core schema: tenants, contacts, conversations, messages, agents, knowledge, products, templates, interactive messages, flows, orders. Seed data for `tastybites` (inactive) |
| `V2__automobile_service_provider.sql` | Adds vehicles, service_records, service_appointments tables. Adds `speedwheels` tenant with seed customers, vehicles, service history, and 14-day appointment slots |

**Key tables and their purpose:**

- `tenants` — one row per business; holds `phone_number_id` (Meta), `system_prompt`, `business_type`, `access_token_encrypted`
- `contacts` — one row per (tenant, wa_id); `wa_id` is the WhatsApp-assigned customer identifier
- `conversations` — one per (tenant, contact); holds `status`, `bot_enabled`, `assigned_agent_id`
- `messages` — every inbound and outbound message; `wa_message_id` has a unique partial index for dedup
- `knowledge_embeddings` — pgvector table, 384-dim, HNSW cosine index; one row per chunk of a knowledge document
- `service_appointments` — slot-based booking; `status` values: AVAILABLE, BOOKED, COMPLETED, CANCELLED

---

## Tenant resolution and isolation

1. Meta sends webhooks to `/webhook` with the `phone_number_id` of the receiving business.
2. `TenantService.resolveActiveTenant(phoneNumberId)` looks up the `tenants` table.
3. `TenantContext.setTenantId(tenant.getId())` sets a `ThreadLocal<UUID>`.
4. `TenantExecutionContext.set(tenant, contact, conversation, phone)` sets the richer context for the AI/tool layer.
5. Both are cleared in `TenantAiService.reply()`'s `finally` block.

**Every repository query that touches tenant-scoped data must include `tenant_id` in the WHERE clause.** A query that omits it and returns data for the wrong tenant is a security incident.

---

## Configuration — required environment variables

| Variable | Used for |
|---|---|
| `DB_URL` | JDBC URL (Cloud SQL Socket Factory in prod) |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials |
| `WHATSAPP_VERIFY_TOKEN` | Meta webhook verification handshake |
| `WHATSAPP_ACCESS_TOKEN` | Fallback Graph API bearer token (per-tenant token preferred) |
| `GCP_PROJECT_ID` | Gemini Vertex AI project |
| `GEMINI_REGION` | Vertex AI region (e.g. `us-central1`) |
| `GEMINI_MODEL` | Gemini model name (default: `gemini-3.5-flash`) |

Optional / defaults:

| Variable | Default | Effect |
|---|---|---|
| `WHATSAPP_MOCK_SEND_ENABLED` | `false` | When `true`, logs instead of calling Meta — useful locally |
| `KNOWLEDGE_REBUILD_ON_STARTUP` | `false` | Force re-embed all knowledge docs at startup |
| `WHATSAPP_GRAPH_API_VERSION` | `v19.0` | Meta Graph API version |
| `SERVER_PORT` | `8080` | HTTP listen port |
| `ai.memory.max-messages` | `20` | LangChain4j memory window |

In production, all secrets are injected via Google Secret Manager (see `cloudbuild.yaml`).
`SPRING_PROFILES_ACTIVE=prod` activates `application-prod.yml`.

---

## Build and deployment

```
# Local build
mvn package -DskipTests
mvn test

# Container (multi-stage, temurin-21)
docker build -t whatsapp-bot .

# Production deploy via Cloud Build
gcloud builds submit --config=cloudbuild.yaml
```

The Cloud Build pipeline (`cloudbuild.yaml`) does:
1. Build Docker image → push to Artifact Registry (`me-central1-docker.pkg.dev`)
2. Deploy to Cloud Run (`whatsapp-bot`, `me-central1`, 0–5 instances, 1 GiB RAM)

The service uses the Cloud SQL Socket Factory connector — no TCP/VPC needed.
The service account `whatsapp-bot-sa` needs `Cloud SQL Client` + `Vertex AI User` roles.

---

## Non-negotiable rules

1. **No new features unless explicitly asked.** Default to refactoring for
   correctness, stability, and scalability of what already exists.

2. **No hardcoding.** Any environment value, business-specific text, magic
   string, retry count, or timeout value is config — not a Java literal.
   See `.claude/skills/no-hardcoding/SKILL.md`.

3. **Nothing crashes the webhook boundary.** `WebhookController.receiveMessage()`
   must always return 2xx to Meta. Every exception in the downstream path
   is caught and logged. See `.claude/skills/resilient-error-handling/SKILL.md`.

4. **Multi-tenant isolation is sacred.** Every query, every tool call, every
   prompt is scoped to the current tenant via `TenantContext` /
   `TenantExecutionContext`. Leaking tenant A's data to tenant B is a
   security incident.

5. **Schema changes go through Flyway only.** Check
   `src/main/resources/db/migration/` before writing any entity or
   repository change. Never enable Postgres extensions via `--database-flags`
   (pgvector on Cloud SQL requires `CREATE EXTENSION` in a migration).

6. **AI behavior changes go through the prompt and tool layer.** No
   per-tenant or per-business-type Java branches. See
   `.claude/skills/multi-tenant-conversational-ai/SKILL.md`.

7. **Every service/component/tool change must leave the class easier to
   unit test.** Favor `@RequiredArgsConstructor` constructor injection. No
   static mutable state beyond the existing `ThreadLocal`-based contexts.

---

## Before making changes

- Read the actual source under `src/main/java`, not this file's description.
- Check `src/main/resources/db/migration/` before touching any entity or repo.
- If the change affects AI responses, re-read
  `.claude/skills/multi-tenant-conversational-ai/SKILL.md` first.
- If touching the webhook path, external API calls, or `@Tool` methods,
  re-read `.claude/skills/resilient-error-handling/SKILL.md` first.

---

## Open implementation tasks

See `.claude/IMPLEMENTATION_TASKS.md` for the full scoped backlog. Summary
of outstanding work as of this writing:

| # | Task | Priority | Status |
|---|---|---|---|
| 1 | Wire conversation memory (`@MemoryId`, `ChatMemoryProvider`) | Critical | Done (AiConfig + WhatsAppAgent already wired) |
| 2 | Resolve customer phone number implicitly in tool methods | Critical | Done (AutomobileServiceTools falls back to context) |
| 3 | Replace ad hoc prompt concatenation with shared template renderer | Medium | Open |
| 4 | Externalize WhatsApp config to `@ConfigurationProperties` class | Low-medium | Open |
| 5 | Convert appointment status strings to typed enum | Low | Open |
| 6 | Add Resilience4j retry/timeout on Gemini + Graph API calls | Medium | Open |

---

## Definition of done for any task

- Compiles (`mvn package`) and passes all tests (`mvn test`).
- No new `@Value`-scattered config, no new literal business text in `.java`
  files, no raw status strings where an enum exists or should.
- No new code path that can throw past the webhook boundary unhandled.
- If you touched tenant-scoped data access, verify the query is still
  tenant-scoped.
- If you touched the AI service or tool layer, verify multi-turn
  conversation memory and identity resolution still work correctly —
  not just the first message.

---

## Known facts (context only — never hardcode)

- GCP project: `whatsapp-bot-yash-2025`, region `me-central1`
- Cloud SQL instance: `whatsapp-bot-db` (Postgres 15)
- Cloud Run service: `whatsapp-bot`, `min-instances=0`, `max-instances=5`
- HikariCP: prod config in `application-prod.yml` (max-pool-size 10)
- Active tenant as of V2 migration: `speedwheels` (SpeedWheels Auto Service Center)
- Inactive tenant: `tastybites` (renamed from `localbites` in V2, `active=false`)
- Embedding model: `all-MiniLM-L6-v2` (384-dim); migration to `text-embedding-004` is on the roadmap but not yet done
- Chat memory: in-JVM `MessageWindowChatMemory`, max 20 messages, not durable across restarts
