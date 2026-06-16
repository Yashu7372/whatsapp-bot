# whatsapp-bot-phase1 — Project Context for Claude Code

This file is read automatically by Claude Code at the start of every session in
this repo. It is the source of truth for how to work on this codebase. Skills
under `.claude/skills/` go deeper on specific topics — read the relevant ones
before touching related code.

## What this is

A multi-tenant SaaS platform: businesses (tenants) deploy AI-powered WhatsApp
bots for their own end customers. Paying customers, real money, real
production traffic. This is not a prototype and should never be treated like
one — every change should be held to the bar of a senior engineer working on
a product other people depend on, not a quick script.

## Stack

- Java 21, Spring Boot 3.3.x, LangChain4j (Vertex AI Gemini backend)
- PostgreSQL + pgvector (RAG), Flyway for all schema changes
- Deployed on GCP Cloud Run (`whatsapp-bot` service, `me-central1`) + Cloud SQL
- Secrets via Google Secret Manager, injected as env vars at deploy time
- WhatsApp Business Cloud API (Graph API) for inbound/outbound messaging

## Non-negotiable rules for any change in this repo

1. **No new features unless explicitly asked.** Default mode is refactor for
   correctness, stability, and scalability of what already exists. If a fix
   requires a small new abstraction (e.g. a config class, a renderer), that's
   fine — net-new product behavior is not, unless asked for.
2. **No hardcoding.** See `.claude/skills/no-hardcoding/SKILL.md`. Any
   environment value, business-specific text, or magic string is a defect,
   not a style nit.
3. **Nothing should crash the request path.** The webhook controller must
   always return 2xx to Meta. See
   `.claude/skills/resilient-error-handling/SKILL.md`.
4. **Multi-tenant isolation is sacred.** Every query, every tool call, every
   prompt must be scoped to the current tenant via `TenantContext` /
   `TenantExecutionContext`. A bug that leaks tenant A's data into tenant B's
   conversation is a security incident, not a bug ticket.
5. **Schema changes go through Flyway only.** Never assume a column or table
   exists without checking `src/main/resources/db/migration`. Never enable
   Postgres extensions via `--database-flags` (this was already discovered
   not to work for pgvector on Cloud SQL — use `CREATE EXTENSION` in a
   migration).
6. **Conversational AI behavior changes go through the prompt template
   system and tool layer, not bespoke per-tenant code.** See
   `.claude/skills/multi-tenant-conversational-ai/SKILL.md`.
7. **Every change that touches a `@Service`, `@Component`, or `@Tool` class
   should leave the class easier to unit test than before, not harder.**
   Favor constructor injection (already the pattern via Lombok
   `@RequiredArgsConstructor`), avoid static mutable state beyond the
   existing `ThreadLocal`-based `TenantContext`/`TenantExecutionContext`.

## Before making changes

- Read the actual current source under `src/main/java`, not assumptions from
  this file. This file describes principles and known facts, not a live
  snapshot of the code.
- Check `src/main/resources/db/migration` for the real current schema before
  writing any repository or entity change.
- If a change affects how the AI responds to customers, re-read
  `.claude/skills/multi-tenant-conversational-ai/SKILL.md` first — this is
  the area most prone to "looks fine in one test message, breaks the next
  turn."

## Known environment facts (context only — never hardcode these as literals)

- GCP project: `whatsapp-bot-yash-2025`, region `me-central1`
- Cloud SQL instance: `whatsapp-bot-db` (Postgres 15, pgvector enabled)
- Cloud Run service: `whatsapp-bot`, currently `min-instances=1` to avoid
  cold-start races killing in-flight Gemini calls
- HikariCP pool capped at 3 connections per instance (Cloud SQL connection
  budget is small — do not raise this without checking the instance tier)
- Local embedding model today is `all-MiniLM-L6-v2` (384-dim, English-only);
  a planned migration to `text-embedding-004` (768-dim, multilingual) is
  on the roadmap but not yet done — don't assume it's already in place.

## Definition of done for any task in this repo

- Compiles and passes existing tests (`mvn test`).
- No new `@Value`-scattered config, no new literal business text in `.java`
  files, no new raw status strings where an enum already exists or should.
- No new code path that can throw past the webhook boundary unhandled.
- If you touched tenant-scoped data access, you re-verified the query is
  still tenant-scoped.
- If you touched the AI service/tool layer, conversation memory and identity
  resolution still work across multiple turns, not just the first message.
