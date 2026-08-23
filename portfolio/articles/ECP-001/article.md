# Why Engineering Needs a Control Plane

*Move AI from open-ended action to governed engineering work.*

**Series:** Engineering Control Plane · **Article 1 / 69**

## The engineering problem

AI-assisted engineering becomes risky when planning, execution and verification are collapsed into one opaque loop. The problem is not whether a model can edit code. The problem is whether the surrounding system can make the work bounded, explainable, repeatable and recoverable. A control plane separates authority from capability: workers may know how to act, but the control plane decides when an action is permitted and when it must stop.

## The task we will follow

**REQ-001 — Fix a failing service without guessing its runtime dependencies**

A defect arrives with a service name and symptom, but the runtime profile, dependent services and required simulators are not yet resolved.

## The request journey

1. **Accept task** — Capture the goal and success condition before touching code.
2. **Resolve context** — Identify repository, service, runtime profile and dependency scope.
3. **Detect gaps** — Keep missing facts explicit instead of silently assuming them.
4. **Plan** — Build a bounded sequence with stop conditions.
5. **Execute** — Delegate only authorized actions to workers.
6. **Verify** — Require tests and evidence before completion.

## What the control plane decides

### Separate authority from capability

The worker can edit files, run tests or inspect logs, but it cannot decide by itself that enough context exists. The control plane owns the transition from one state to the next. This is the core architectural boundary that turns an AI tool into an engineering system.

### Represent work as state

The task is a durable record: goal, known facts, unknowns, plan, attempts, outputs, evidence and verdict. Because state is explicit, a failed attempt does not disappear into a chat transcript. The system can resume, compare, replay and explain what changed.

### Make stopping a first-class outcome

A stop is not a failure of the system. Missing context, repeated test failure, unsafe scope or incomplete evidence should all produce explicit bounded states. A trustworthy system must be able to refuse to continue.

## Implementation shape

- Task record is the unit of control, not a free-form conversation.
- Workers expose typed capabilities; the orchestrator owns sequencing.
- Every transition can emit evidence and a reason code.
- The same lifecycle supports human tools, deterministic scripts and AI workers.

## What this boundary prevents

This design is deliberately defensive. For REQ-001, the system should never convert a missing fact into an implicit assumption just because execution is possible. It should also avoid treating a successful command as proof that the engineering goal was achieved. The control record keeps the requested outcome, the context used, the actions taken, and the evidence produced as separate concerns. That separation matters when a run is interrupted, when another worker resumes the task, when a human reviews the decision, or when the same scenario is replayed later. The objective is not more automation at any cost; it is automation whose authority, scope and completion criteria remain inspectable.

## Evidence, not confidence theater

The first implementation milestone is intentionally small: a terminal-first orchestrator with a task record, worker registry, explicit transitions and validation gates. The claim is not that the entire platform is complete. The verified claim is that the architecture defines a concrete authority boundary and a deterministic lifecycle that later engines can plug into.

## The point

The agent is a capability. The control plane is the authority boundary.

## What comes next

Article 2 turns that boundary into a repeatable runtime using profile-driven environments.
