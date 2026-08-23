# A Knowledge Spine, Not a Bigger Prompt

*Durable engineering understanding should be queryable, scoped and evidence-backed.*

**Series:** Engineering Control Plane · **Article 5 / 69**

## The engineering problem

Large engineering systems contain multiple kinds of knowledge: code structure, service ownership, runtime dependencies, domain rules, operational history, architecture decisions, incidents, tests and evidence. Dumping everything into retrieval creates noisy context and weakens authority boundaries. A Knowledge Spine stores durable facts and relationships, while a Knowledge Lens selects the subset required for the current task.

## The task we will follow

**REQ-005 — Change a baggage rule without missing downstream operational effects**

The code change is small, but the rule participates in projections, alerts and operational workflows. The task needs the relevant relationships and rationale, not every document containing the word baggage.

## The request journey

1. **Understand task** — Extract entities, intent and required decision type.
2. **Query spine** — Find candidate facts and relationships with provenance.
3. **Apply lens** — Select the context slice needed for this task.
4. **Detect gaps** — Mark missing ownership, rule or dependency facts.
5. **Build context** — Assemble compact evidence-backed task context.
6. **Plan safely** — Use only resolved context to authorize implementation.

## What the control plane decides

### Knowledge is typed

A source file, domain rule, architecture decision, runtime observation and historical incident are not interchangeable chunks. Typed knowledge lets retrieval reason about authority, freshness and relevance instead of treating every text fragment equally.

### Relationships matter more than keyword proximity

For an implementation task, the useful path may be Rule → Evaluator → Projection → Alert → UI. The Knowledge Spine makes those relationships explicit so the system can retrieve a connected slice rather than a bag of similar passages.

### The lens is task-specific

A code implementation lens, incident diagnosis lens and architecture review lens should retrieve different facts for the same entity. This avoids building multiple disconnected graphs while still preventing every task from seeing everything.

## Implementation shape

- Knowledge objects have type, source, freshness, confidence and provenance.
- Relations can come from deterministic extractors, configuration, code analysis and AI-assisted inference with different trust levels.
- Lenses are reusable task policies that choose relation depth, knowledge types and evidence thresholds.
- Unknown required facts remain explicit and can trigger gap resolution rather than wider blind retrieval.

## What this boundary prevents

This design is deliberately defensive. For REQ-005, the system should never convert a missing fact into an implicit assumption just because execution is possible. It should also avoid treating a successful command as proof that the engineering goal was achieved. The control record keeps the requested outcome, the context used, the actions taken, and the evidence produced as separate concerns. That separation matters when a run is interrupted, when another worker resumes the task, when a human reviews the decision, or when the same scenario is replayed later. The objective is not more automation at any cost; it is automation whose authority, scope and completion criteria remain inspectable.

## Evidence, not confidence theater

The first useful version does not require a giant autonomous graph. Start with a small typed model, provenance on every fact, deterministic relations where they can be extracted, and targeted semantic assistance where ambiguity remains. The quality gate is whether the retrieved slice can explain why each fact is present and which unresolved facts still block the task.

## The point

The Knowledge Spine remembers broadly. The Knowledge Lens reveals narrowly.

## What comes next

Article 6 will focus on knowledge gaps: how the system recognizes that it does not know enough and refuses to guess.
