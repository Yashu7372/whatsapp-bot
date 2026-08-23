# Simulation Is Part of the Runtime

*Enterprise development should not stop because a database, broker or upstream system is unavailable.*

**Series:** Engineering Control Plane · **Article 3 / 69**

## The engineering problem

Enterprise services rarely run in isolation. They depend on Oracle or PostgreSQL databases, message brokers, HTTP services, schedulers and external event producers. On restricted laptops or weekends, those systems may be unreachable. Hard-coding mocks inside application code changes the system under test. The better boundary is outside the application: the runtime profile selects real or simulated infrastructure while preserving the application's contracts.

## The task we will follow

**REQ-003 — Reproduce an event-processing defect with enterprise dependencies offline**

The service expects database state, broker messages and an upstream event. The environment must reproduce enough behavior to debug the defect without modifying production code paths.

## The request journey

1. **Read profile** — Determine dependency mode for the task.
2. **Provision state** — Create deterministic database or file-backed state.
3. **Start simulators** — Launch broker and HTTP/event simulators.
4. **Seed scenario** — Load the exact events and records needed for the defect.
5. **Run service** — Start the unmodified application contract.
6. **Capture evidence** — Store requests, events, state transitions and logs.

## What the control plane decides

### Simulate contracts, not internals

A simulator should mimic the boundary the application actually consumes: an HTTP endpoint, topic, queue, SQL contract or event payload. It should not duplicate internal application logic, because that would make the test self-confirming.

### Scenarios must be deterministic

A named scenario contains seed data, event ordering, delays and expected outputs. Re-running the same scenario should produce the same starting conditions. Determinism is what makes simulation useful for debugging and regression evidence.

### Real and simulated modes share one profile model

The application should not need separate orchestration logic for each environment. A profile chooses a dependency provider while the rest of the lifecycle—validate, start, observe, stop, collect evidence—remains unchanged.

## Implementation shape

- Dependency providers implement a common lifecycle: validate, start, health, stop.
- Scenario files define seed data and events independently of application code.
- Simulators expose the same external contract used by the service.
- Evidence collection keeps simulator inputs and application outputs together.

## What this boundary prevents

This design is deliberately defensive. For REQ-003, the system should never convert a missing fact into an implicit assumption just because execution is possible. It should also avoid treating a successful command as proof that the engineering goal was achieved. The control record keeps the requested outcome, the context used, the actions taken, and the evidence produced as separate concerns. That separation matters when a run is interrupted, when another worker resumes the task, when a human reviews the decision, or when the same scenario is replayed later. The objective is not more automation at any cost; it is automation whose authority, scope and completion criteria remain inspectable.

## Evidence, not confidence theater

The important validation is not that a mock server answered 200. The evidence pack should show the scenario definition, emitted events, application logs, resulting state and assertions. That allows the same defect reproduction to become a regression scenario rather than a one-time debugging trick.

## The point

Keep the application honest. Simulate the environment around it.

## What comes next

Article 4 adds runtime context retrieval so the control plane knows what is actually running before deciding what to do.
