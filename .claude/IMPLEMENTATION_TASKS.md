# Implementation Tasks

A scoped backlog for Claude Code to work through, one task at a time. Each
task is sized to be its own reviewable diff — do not combine multiple tasks
into a single change unless explicitly told to. Run them as separate batch
invocations against this repo, in the order listed; later tasks assume
earlier ones are done.

For each task: read the referenced skill file fully before starting. Run
`mvn test` before considering a task complete. If a task can't be completed
without inventing new product behavior (not just refactoring/fixing
existing behavior), stop and flag that instead of improvising scope.

---

## Task 1 — Wire conversation memory into the AI service layer

**Skill:** `.claude/skills/multi-tenant-conversational-ai/SKILL.md`
**Priority:** Critical — this is the root cause of the bot forgetting
information mid-conversation (e.g. re-asking for a phone number that was
already given two turns earlier).

**Files involved:** `AiConfig.java`, `WhatsAppAgent.java`,
`TenantAiService.java`

**Scope:**
- Add a `ChatMemoryProvider` bean to `AiConfig` using
  `MessageWindowChatMemory`, with the max-messages value as a configurable
  property (not a literal).
- Add a `@MemoryId String conversationId` parameter to
  `WhatsAppAgent.chat(...)`.
- Update `TenantAiService.reply(...)` to pass `conversation.getId().toString()`
  as that memory ID.

**Done when:** sending three or more sequential WhatsApp messages in the
same conversation (e.g. give phone number → ask for orders → ask to book an
appointment) does not require re-providing information already given
earlier in the same conversation, verified via a live test against the
deployed Cloud Run service with `gcloud alpha run services logs tail`.

---

## Task 2 — Resolve customer phone number implicitly in the tool layer

**Skill:** `.claude/skills/multi-tenant-conversational-ai/SKILL.md`
**Priority:** Critical — same user-facing symptom as Task 1, different
cause; do both before considering the conversation flow fixed.

**Files involved:** `AutomobileServiceTools.java`, anything else with a
`@Tool` method accepting a phone-number-shaped parameter.

**Scope:**
- Each affected tool method falls back to
  `TenantExecutionContext.getRequired().contact()`'s known phone/waId when
  the LLM passes a null/blank phone argument, instead of relying on the
  model to ask the user for it.
- Update the shared system prompt's tool-usage policy section (see Task 3)
  to explicitly state the current customer's number is always already
  known and should never be requested, except when the conversation is
  clearly about a different person.

**Done when:** a fresh conversation (no number given anywhere yet) can go
straight to "show me my orders" or "book an appointment" without the bot
asking for a phone number at all, because it's already resolved from
context.

---

## Task 3 — Replace ad hoc prompt string concatenation with a shared template

**Skill:** `.claude/skills/multi-tenant-conversational-ai/SKILL.md` and
`.claude/skills/no-hardcoding/SKILL.md`
**Priority:** Medium — not a live bug, but blocks clean onboarding of new
business types and is where the next "let's just hardcode this for one
tenant" shortcut would otherwise happen.

**Files involved:** `TenantAiService.java` (remove `GLOBAL_GUARDRAILS`
literal), new `PromptTemplateRenderer` component, new
`src/main/resources/prompts/system-template.md`

**Scope:**
- Create one versioned template resource with named placeholder sections
  (identity/role, platform guardrails, tool-usage policy, approved
  templates, knowledge base context, tenant business context).
- Create a single renderer component used for every tenant regardless of
  business type — no per-tenant or per-vertical Java files or code
  branches.
- `TenantAiService` calls the renderer instead of building the prompt via
  string concatenation.

**Done when:** the platform guardrail text can be edited by changing the
resource file alone, with no Java recompile, and the rendered prompt for
an existing tenant is functionally equivalent to today's (no regression in
tone or behavior).

---

## Task 4 — Externalize WhatsApp integration config

**Skill:** `.claude/skills/no-hardcoding/SKILL.md`
**Priority:** Low-medium — cleanup, not a live bug.

**Files involved:** `WhatsAppGraphClient.java`

**Scope:** Replace the three separate `@Value` fields
(`mockSendEnabled`, `graphApiVersion`, `fallbackAccessToken`) with one
`@ConfigurationProperties(prefix = "whatsapp")` class, validated so a
missing required value fails fast at startup with a clear message.

**Done when:** all WhatsApp-related config lives in one typed class, and
deploy-time behavior is unchanged.

---

## Task 5 — Convert appointment status strings to a typed enum

**Skill:** `.claude/skills/no-hardcoding/SKILL.md`
**Priority:** Low — cleanup, not a live bug.

**Files involved:** `AutomobileServiceTools.java`, `ServiceAppointmentEntity`
and its repository.

**Scope:** Replace the raw `"AVAILABLE"` / `"BOOKED"` / `"CANCELLED"`
string constants with a proper enum used consistently from the entity
layer through to the tool layer.

**Done when:** there are no remaining raw status string literals being
compared with `.equals(...)` for appointment status anywhere in the
codebase.

---

## Task 6 — Resilient external API calls

**Skill:** `.claude/skills/resilient-error-handling/SKILL.md`
**Priority:** Medium — hardening, do after Tasks 1–2 since those fix the
more visible bug first.

**Files involved:** wherever Gemini and WhatsApp Graph API calls are made
(`AiConfig`/`WhatsAppAgent` chat invocation path, `WhatsAppGraphClient`)

**Scope:** Add bounded timeout + bounded retry with backoff (Resilience4j
`@Retry`/`@TimeLimiter`, configured via `application.yml`, not hand-rolled
loops) around outbound calls to Gemini and the WhatsApp Graph API, so a
transient failure degrades gracefully instead of risking a request-path
crash or unbounded retry storm.

**Done when:** a simulated transient failure (e.g. temporarily blocking
network access to one dependency) results in a logged, bounded number of
retries and a clean fallback response to the customer, not a crash or
infinite retry loop.
