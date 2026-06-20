---
name: no-hardcoding
description: Use whenever adding or touching configuration values, business-specific text, status/category strings, or anything environment-specific in this codebase. Defines what counts as hardcoding here and the required externalization pattern.
---

# No Hardcoding

This is a multi-tenant platform. Anything that is true for one tenant,
one environment, or one point in time but gets written as a literal in
`.java` source is a defect that will surface later as a production bug,
not a style preference.

## What counts as hardcoding in this repo

1. **Environment-specific values** — project IDs, regions, model names,
   API versions, URLs, ports, pool sizes, timeouts. These belong in
   `application-{profile}.yml` (or Secret Manager-backed env vars), bound
   through `@ConfigurationProperties`, never scattered `@Value` fields with
   inline defaults sprinkled across unrelated classes.

   Current known offenders to fix opportunistically when touched:
   `WhatsAppGraphClient` has `mockSendEnabled`, `graphApiVersion`, and
   `fallbackAccessToken` as three separate `@Value` fields. These should be
   one `@ConfigurationProperties(prefix = "whatsapp")` record/class so the
   whole WhatsApp integration's config lives in one typed place.

2. **Business-specific text baked into Java code.** A platform rule, a
   tenant's tone of voice, a guardrail sentence — none of it should live as
   a `String` literal inside a `@Service` class.

   Current known offender: `TenantAiService.GLOBAL_GUARDRAILS` is a static
   final text block embedded directly in the class. This must move to a
   versioned resource template (see the
   `multi-tenant-conversational-ai` skill for the full prompt template
   design) — not because it's long, but because changing platform-wide
   guardrail copy currently requires a Java recompile and redeploy instead
   of an editable resource.

3. **Magic strings used as enums.** `AutomobileServiceTools` uses raw
   `String` constants (`"AVAILABLE"`, `"BOOKED"`, `"CANCELLED"`) for
   appointment status. If the entity layer doesn't already have a typed
   enum for this, add one and use it everywhere instead of comparing
   strings — a typo in a literal should be a compile error, not a runtime
   silent mismatch.

4. **Per-tenant or per-business-type code branches.** If you find yourself
   writing `if (tenant.getType().equals("AUTOMOBILE")) { ... } else if (...)`
   anywhere outside of a clearly-justified, centrally documented
   capability/tool registry, that's hardcoding of business logic that
   should instead be data-driven (a tenant config row, a tool-allowlist
   mapping) — see the conversational AI skill for the specific pattern
   used for tool allowlisting.

5. **Anything duplicated in two or more places.** If the same status
   string, the same URL template, the same default value appears in more
   than one file, that's a sign it should be a single named constant or
   config value, not copy-pasted.

## Required pattern

- One `@ConfigurationProperties` class per cohesive integration (WhatsApp,
  Gemini, future Stripe billing), bound from `application.yml`, validated
  with `@Validated` + Jakarta `@NotBlank`/`@Positive` where it matters, so
  missing config fails fast at startup with a clear message instead of a
  silent null deep in a request.
- Business text and prompts live as resources under
  `src/main/resources/prompts/`, loaded once at startup or cached, never
  rebuilt as ad hoc string concatenation per request beyond simple
  placeholder substitution.
- Status/category values are enums with a clear `displayName()` if a
  human-readable form is needed, never raw strings compared with
  `.equals(...)`.

## Anti-pattern to never introduce

Do not "fix" hardcoding by adding a new hardcoded fallback default deeper
in the call stack (e.g. `phone != null ? phone : "0000000000"`). Externalizing
config means making the real value configurable and explicit, not hiding a
fake default further down.
