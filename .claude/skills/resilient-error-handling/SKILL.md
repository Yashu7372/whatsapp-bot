---
name: resilient-error-handling
description: Use whenever touching the webhook controller, any @Tool method, any call to an external API (Gemini, WhatsApp Graph API, future Stripe), or any repository save that could violate a constraint. Defines how this codebase handles failure without crashing the request path.
---

# Resilient Error Handling

The core rule: **a failure anywhere downstream must never become an
unhandled exception at the webhook boundary.** Meta retries on non-2xx
responses, and a crash loop on retries has already caused real outages in
this project (a Flyway migration failure once took the whole app down on
every boot, and a duplicate-message constraint violation once bubbled all
the way up to an unhandled 500). Neither should be possible going forward.

## Layered handling

**1. Tool layer (`@Tool` methods, e.g. `AutomobileServiceTools`).**
Expected "not found" or "nothing to do" outcomes are not exceptions — they
are descriptive string returns the LLM can reason about, exactly like the
existing pattern (`"NO_CUSTOMER_FOUND for phone=..."`,
`"NO_AVAILABLE_SLOTS for date=..."`). Keep this pattern. Reserve actual
thrown exceptions in tool methods for genuinely unexpected conditions
(e.g. a malformed date that can't be parsed at all, a DB call that fails) —
and even then, those should be caught one layer up so the AI service call
degrades to a clear fallback message rather than propagating.

**2. AI service layer (`TenantAiService`, `WhatsAppAgent`).** Wrap the
LangChain4j call so a Gemini-side failure (timeout, transient
`UnavailableException`, rate limit) results in a safe, customer-facing
fallback string, not a thrown exception:

```java
public String reply(TenantEntity tenant, ContactEntity contact,
                     ConversationEntity conversation,
                     String customerPhoneNumber, String userMessage) {
    try {
        // existing context setup + whatsAppAgent.chat(...) call
    } catch (Exception e) {
        log.error("AI reply generation failed tenant={} conversation={}",
                tenant.getId(), conversation.getId(), e);
        return "Sorry, I'm having a brief technical issue — could you " +
               "resend that in a moment?";
    } finally {
        TenantExecutionContext.clear();
        TenantContext.clear();
    }
}
```

**3. Webhook controller.** The HTTP boundary always returns 200 once the
payload is structurally valid, regardless of what happens during
processing. Heavy work (AI generation, sending the reply) should not block
the response Meta sees, and any exception in that background path is
caught and logged, never rethrown to a thread Meta is waiting on.

**4. Repository / persistence layer.** Known, expected constraint
violations (the unique `WA_MESSAGE_ID` index exists specifically because
Meta redelivers webhooks) are caught and treated as a no-op, not a crash:

```java
try {
    messageRepository.save(message);
} catch (DataIntegrityViolationException e) {
    log.warn("Duplicate inbound message ignored waMessageId={}",
            message.getWaMessageId());
}
```

Before "fixing" a constraint violation by deleting the constraint or
deleting the unique index — don't. The constraint is correct; the calling
code needs to handle the expected violation.

**5. External API calls (Gemini, WhatsApp Graph API).** These need
bounded timeouts and bounded retries, not infinite or absent ones. Prefer
Resilience4j (`@Retry`, `@CircuitBreaker`, `@TimeLimiter`) configured via
`application.yml` over hand-rolled retry loops — it's the idiomatic Spring
Boot choice and keeps retry policy externally configurable rather than
buried in code (also see the `no-hardcoding` skill: retry counts, backoff,
and timeout values are config, not literals).

**6. Any REST surface beyond the webhook** (dashboard APIs, once built)
gets a `@RestControllerAdvice` global handler that returns structured JSON
errors (`{"error": "..."}`) and never leaks a stack trace to the client.

## Logging discipline

- Always include `tenantId`, `conversationId`, and (when relevant)
  `waMessageId` on error and warn logs — this is the only way to trace an
  issue back to a specific customer conversation in Cloud Logging.
- Never log full inbound webhook payloads or full customer messages at
  `INFO` level in production — they may contain PII. Log identifiers and
  short, non-sensitive summaries instead.
- A caught exception is still logged at `ERROR` or `WARN` with the
  exception object passed to the logger (not just `e.getMessage()`) so the
  stack trace survives in Cloud Logging for debugging.

## Anti-patterns to never introduce

- `catch (Exception e) {}` with no logging — silent failure is worse than a
  crash, because nobody finds out it happened.
- Catching `Throwable` broadly to "make it stop crashing" without
  understanding what's actually failing underneath.
- Retrying indefinitely with no backoff or cap, which just turns a
  transient failure into a sustained load spike against Gemini or the
  WhatsApp Graph API.
