# Profiles Before Processes

*A repeatable engineering runtime starts with explicit environment intent.*

**Series:** Engineering Control Plane · **Article 2 / 69**

## The engineering problem

Local engineering workflows often depend on invisible machine state: shell variables, remembered commands, IDE run configurations, manually started dependencies and credentials scattered across files. AI automation makes this worse because an agent can execute quickly against the wrong environment. A profile-driven runtime makes the intended execution context explicit before any process starts.

## The task we will follow

**REQ-002 — Start reports-service locally with the correct dependencies**

The service needs a JAR path, environment variables, database connectivity and messaging behavior. The developer should not reconstruct that knowledge from memory every time.

## The request journey

1. **Select profile** — Choose the named runtime intent for the task.
2. **Resolve variables** — Load defaults, overrides and protected values.
3. **Validate files** — Confirm JARs, configs and commands exist.
4. **Resolve dependencies** — Determine which real or simulated dependencies are required.
5. **Start processes** — Launch only after validation passes.
6. **Capture runtime** — Persist PID, ports, logs and effective configuration.

## What the control plane decides

### Profiles are contracts

A profile is more than a bag of environment variables. It declares what should run, where artifacts come from, which dependencies are expected and what validation must pass first. The profile becomes an executable contract for the environment.

### Secrets remain references

The profile can declare that a value is required without committing the secret itself. A resolver can load encrypted values, OS environment values or approved secret providers. The durable configuration records intent, not credentials.

### Effective configuration is evidence

Before execution, the control plane can print or persist the resolved non-secret configuration. That record makes debugging reproducible because the developer can see exactly which profile, artifact and dependency mode were used.

## Implementation shape

- Profiles declare process commands, artifact locations, working directories and environment requirements.
- Environment values support defaults and local overrides while secrets remain external references.
- Preflight validation runs before any child process is launched.
- Runtime state records PID, start time, ports and log locations for later control.

## What this boundary prevents

This design is deliberately defensive. For REQ-002, the system should never convert a missing fact into an implicit assumption just because execution is possible. It should also avoid treating a successful command as proof that the engineering goal was achieved. The control record keeps the requested outcome, the context used, the actions taken, and the evidence produced as separate concerns. That separation matters when a run is interrupted, when another worker resumes the task, when a human reviews the decision, or when the same scenario is replayed later. The objective is not more automation at any cost; it is automation whose authority, scope and completion criteria remain inspectable.

## Evidence, not confidence theater

The runtime profile is useful only if it can fail early. Validation should reject missing artifacts, unresolved required variables, port conflicts and incompatible dependency modes before launching the application. Successful startup is then tied to a concrete resolved profile instead of an IDE-specific state.

## The point

Do not start a process and then discover its environment. Resolve the environment, then authorize the process.

## What comes next

Article 3 uses those profiles to create isolated simulation instead of depending on unavailable enterprise infrastructure.
