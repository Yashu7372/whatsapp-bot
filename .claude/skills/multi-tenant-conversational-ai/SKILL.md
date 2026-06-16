---
name: multi-tenant-conversational-ai
description: Use whenever touching TenantAiService, WhatsAppAgent, AiConfig, any @Tool class, or anything that builds the system prompt sent to Gemini. Defines how customer identity, conversation memory, and per-tenant prompt content must work so the bot behaves like a real product, not a stateless demo.
---

# Multi-Tenant Conversational AI

This skill covers the three things that make a WhatsApp AI agent feel
broken when done wrong: forgetting what was just said, asking for
information the system already has, and needing custom code per business
to change how it talks. All three have a single root cause in this
codebase's current state: the AI service layer treats every call as
stateless and treats tenant-specific behavior as freeform text rather than
structured, template-driven config.

## 1. Customer identity is already known — never ask for it

Every inbound WhatsApp webhook already contains the sender's number.
`WhatsAppWebhookParser` extracts it as `fromWaId`, which becomes
`contact.getWaId()` and is passed into `TenantAiService.reply(...)` as
`customerPhoneNumber`, available for the whole turn via
`TenantExecutionContext`. The model should never need to ask "what's your
number?" for the current customer — it already has it from the platform,
the same way a human agent would already see the sender's number on their
screen.

**Required pattern for any `@Tool` method that takes a phone number
parameter** (`lookupCustomerByPhone`, `listCustomerVehicles`,
`getCustomerServiceHistory`, `bookAppointment`, etc.): when the LLM
supplies a blank/null phone argument, the tool resolves it from
`TenantExecutionContext.getRequired()` instead of returning a
"need more info" sentinel that causes the model to ask the user. Only ask
the user for a number when the conversation is genuinely about *someone
else* (e.g. "book this for my wife's car too") and that has been made
explicit — and even then, the system prompt should make clear this is the
exception, not the default.

**Required system prompt instruction** (goes in the shared template's tool
policy section, see below, not duplicated per tool): the current
customer's identity and phone number are always already known from
context; the assistant must never ask for it. It should only ask a
clarifying question if a tool result explicitly signals the lookup
genuinely failed (e.g. `NO_CUSTOMER_FOUND` because the contact record
itself doesn't exist yet) — and at that point it should ask once, plainly,
not repeatedly mid-flow.

## 2. Conversation memory must exist and be scoped per conversation

Today, `WhatsAppAgent.chat(...)` has no `@MemoryId` parameter and
`AiConfig` defines no `ChatMemoryProvider` — every call to Gemini is fully
stateless. This is why a customer can give their number on one turn, get
their orders, and then be asked for the same number again on the very next
turn for a different tool: the model has zero memory the second call ever
happened.

**Required wiring:**

- `AiConfig` exposes a `ChatMemoryProvider` bean
  (`MessageWindowChatMemory`, a reasonable window such as 20–30 messages —
  tune as a config value, not a magic literal, per the `no-hardcoding`
  skill).
- `WhatsAppAgent.chat(...)` takes a `@MemoryId String conversationId`
  parameter.
- `TenantAiService.reply(...)` passes `conversation.getId().toString()` as
  that memory ID — not the WhatsApp ID/phone number, because this is a
  multi-tenant platform and `conversation.getId()` is already guaranteed
  unique per tenant+contact pair (`UK_CONVERSATIONS_TENANT_CONTACT`),
  whereas a phone number alone is not guaranteed unique across tenants.

**Durability caveat to flag, not silently ignore:** an in-memory
`ChatMemoryProvider` lives in the JVM heap. It is wiped on every Cloud Run
instance restart/redeploy and is not shared if the service ever scales to
more than one instance. This is acceptable for the current single-instance
deployment but is a known limitation, not a finished state — if asked to
make this durable, the correct direction is a custom `ChatMemoryStore`
backed by the existing `MESSAGES` table (already populated for every
inbound/outbound message) rather than a new bespoke memory table.

## 3. One shared prompt template, not one file or one code path per business

Today `TenantAiService.reply(...)` builds the system prompt by string
concatenation: `tenant.getSystemPrompt() + GLOBAL_GUARDRAILS +
templateContext + ragContext`, with `GLOBAL_GUARDRAILS` hardcoded directly
in the Java class. This does not scale cleanly to many tenants and many
business verticals, and it's exactly the kind of thing that tempts a
future "just add a new branch for restaurant tenants" / "just add a new
Java file for car dealerships" shortcut — which is the wrong direction.

**Required architecture:**

- A single versioned prompt template (e.g.
  `src/main/resources/prompts/system-template.md`) with named, clearly
  delimited sections — identity/role, platform guardrails, tool-usage
  policy, approved-templates context, knowledge-base context, tenant
  business context — rendered by one shared `PromptTemplateRenderer`
  component used for every tenant, every business type.
- Tenant- and business-type-specific behavior comes from **structured
  config data** (tenant's stored business type/vertical, tone, language,
  free-text business description), substituted into the template's named
  placeholders — never from a different prompt file, a different Java
  class, or an `if (businessType == ...)` branch in the AI service.
- Tool allowlisting per business vertical (e.g. `automobileServiceTools`
  only makes sense for automotive tenants) should be driven by a
  business-type → tool-set mapping (config or DB-backed), not hardcoded in
  the `@AiService(tools = {...})` annotation as a fixed list for all
  tenants. If a new vertical needs a different tool set, that's a config
  row, not a new annotation value requiring a recompile.
- The platform guardrails text (today's `GLOBAL_GUARDRAILS` constant)
  becomes one section of the shared template, editable as a resource, with
  the same "no hardcoding" reasoning as any other business text.

## What "good" looks like here

A new tenant in a brand-new vertical should be onboardable by inserting
config rows (business type, tone, allowed tools, system prompt business
context) — zero new Java files, zero new prompt files, zero new code
branches. If achieving that for a given request requires touching the
renderer or the tool-allowlist mechanism once, that's the right kind of
change; duplicating logic per tenant is not.
