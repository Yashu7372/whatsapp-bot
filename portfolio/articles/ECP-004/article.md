# Before Acting, Know What Is Running

*Runtime context turns process control into evidence-driven engineering.*

**Series:** Engineering Control Plane · **Article 4 / 69**

## The engineering problem

Static configuration tells us what should be running. Engineering decisions often depend on what is actually running: which process owns a port, which JAR version started, whether a simulator or real dependency is active, what health endpoints report and where logs are flowing. Without runtime context, an automation system may restart the wrong process, diagnose stale logs or test against an unexpected dependency.

## The task we will follow

**REQ-004 — Diagnose why an API is not responding after a local restart**

The configured profile says the service should listen on one port, but another Java process may still own it and the active logs may belong to a previous run.

## The request journey

1. **Read intent** — Load expected process, artifact, ports and dependencies.
2. **Observe processes** — Inspect PIDs, commands, ports and start times.
3. **Probe health** — Call configured health endpoints and dependency checks.
4. **Correlate logs** — Associate log streams with the active runtime instance.
5. **Compare state** — Identify drift between intended and observed runtime.
6. **Choose action** — Restart, stop, diagnose or refuse based on evidence.

## What the control plane decides

### Observed state is versioned context

Runtime discovery should produce a structured snapshot rather than scattered command output. A snapshot can include process identity, artifact checksum, ports, dependency health, environment fingerprint and log pointers.

### Drift becomes a decision input

If the active process does not match the profile, the system should surface drift explicitly. The correct next step may be to stop an orphaned process, change the profile or refuse to run a destructive command.

### Context has provenance

Every observation should record how and when it was obtained. A port scan, process query, health endpoint and log parser have different reliability. Provenance prevents stale or weak observations from silently becoming facts.

## Implementation shape

- Collectors gather process, port, health and log metadata through small adapters.
- The normalized snapshot separates intended state from observed state.
- Drift rules are deterministic and produce reason codes.
- Higher-level planning consumes the snapshot rather than raw shell output.

## What this boundary prevents

This design is deliberately defensive. For REQ-004, the system should never convert a missing fact into an implicit assumption just because execution is possible. It should also avoid treating a successful command as proof that the engineering goal was achieved. The control record keeps the requested outcome, the context used, the actions taken, and the evidence produced as separate concerns. That separation matters when a run is interrupted, when another worker resumes the task, when a human reviews the decision, or when the same scenario is replayed later. The objective is not more automation at any cost; it is automation whose authority, scope and completion criteria remain inspectable.

## Evidence, not confidence theater

A runtime context engine should be testable without AI. Given process-list, port and health-probe inputs, it should construct the same normalized snapshot and identify the same drift. AI can later interpret that snapshot, but collection and normalization remain deterministic.

## The point

Configuration is intended state. Runtime context is observed state. Safe automation needs both.

## What comes next

Article 5 connects runtime observations with durable engineering knowledge through the Knowledge Spine.
