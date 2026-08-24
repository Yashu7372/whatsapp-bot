# Before Acting, Know What Is Running

**Runtime context turns process control into evidence-driven engineering.**

**Article 4 / 70 · Engineering Control Plane**

**Task:** REQ-004 — Diagnose why an API is not responding after a local restart

**Thesis:** Configuration is intended state. Runtime context is observed state. Safe automation needs both.

## Engineering Tension

The central tension is **Declared intent ↔ Observed reality**. Both sides are legitimate. Optimizing only for declared intent produces one class of failure; optimizing only for observed reality produces another. The engineering problem is to build a system that can move between them deliberately rather than letting whichever tool is currently executing make that trade-off implicitly.

For **REQ-004**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate the next step?” but “what state must exist before the next step is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## The Real Problem

Configuration describes what should be running. Engineering decisions depend on what is actually running: process identity, artifact version, ports, health, dependency mode and current logs. Automation that acts only from configuration can be perfectly consistent and completely wrong.

For **REQ-004**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate a corrective action?” but “what state must exist before a corrective action is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## Of Course This Happened

**Incident type: generalized engineering scenario.** A restart command reports success, but the API still serves old behavior. The profile is correct, the new JAR is correct, and the logs look healthy. Eventually someone discovers an orphaned Java process still owns the port. Apparently the previous process did not receive the memo that configuration had moved on.

The lesson is not “kill Java harder.” It is that intended state and observed state are different data sets and must be compared before the system chooses an action.

This scenario is used as a teaching device, not claimed as a documented production incident. Its value is that the failure mode is common, observable and directly related to the architectural tension in this article.

## Why the Naive Approach Fails

The naive implementation usually optimizes for the shortest path: accept the request, gather some context, perform an action, run a convenient check and declare completion. That shape is attractive because it compresses orchestration into one loop. It is also where assumptions become invisible. For REQ-004, a successful command is not proof that the requested engineering outcome has been achieved.

For **REQ-004**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate execution?” but “what state must exist before execution is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## The System Idea

The system idea in this article is the **Runtime Context Engine**. Its responsibility is deliberately narrower than “be intelligent.” It creates a controlled boundary around the task: represent state explicitly, resolve only the context needed for the current decision, authorize bounded actions, preserve unknowns, and attach evidence to transitions. It does **not** magically know production truth, replace engineering judgment, or convert weak evidence into certainty.

For **REQ-004**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate system behavior?” but “what state must exist before system behavior is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## When Would You Actually Use This?

Use this pattern when the cost of an incorrect action is meaningful, when work crosses multiple tools or dependencies, when tasks need to resume or be audited, when AI participates in implementation, or when success depends on several independent checks. It is especially valuable for enterprise development, migration work, incident diagnosis, release automation and multi-step engineering workflows.

It is probably unnecessary for a disposable script whose correctness is obvious from one deterministic command. The goal is not to wrap every shell command in ceremony. The goal is to add control where uncertainty, side effects or verification cost justify it.

For REQ-004, the pattern is useful because the task contains at least one unknown that can materially change the correct next action. That unknown is part of the state, not an inconvenience to be hidden.

## One Concrete Task

**REQ-004 — Diagnose why an API is not responding after a local restart**

The configured profile says the service should listen on one port, but another process may still own it and the active logs may belong to a previous run.

## Task Snapshot — Before

**TASK:** REQ-004
**GOAL:** Restore the expected API runtime
**KNOWN:** intended profile, expected port, new artifact
**UNKNOWN:** actual port owner, active artifact, which logs belong to current process
**CONFIDENCE:** 0.39
**NEXT CONTROL ACTION:** Collect an observed runtime snapshot

## Bounded Context for This Task

The bounded context for REQ-004 contains only information that can change the next decision. That includes the task goal, the known facts listed above, the unresolved facts that can block execution, the relevant implementation boundary and the evidence needed for verification. It deliberately excludes unrelated repositories, historical documents and broad domain material unless a relationship demonstrates why they matter.

This is not merely a token-saving optimization. Bounded context improves authority. If every available fact is injected, the system can no longer explain why a particular fact influenced the decision. A task-scoped context can attach provenance and relevance to each item, making it possible to say: this fact is present because it establishes ownership; this observation is present because it determines runtime drift; this test is present because it proves a required behavior.

The unknown set is equally important. In this article, the system is allowed to stop when an unknown is necessary for the next transition. It should not widen retrieval indefinitely or replace the missing fact with model confidence.

## Request Journey

1. **Read intent** — Load expected artifact, command, ports and dependencies.
2. **Observe processes** — Inspect PIDs, commands, ports and start times.
3. **Probe health** — Call configured health checks.
4. **Correlate logs** — Associate current logs with active runtime identity.
5. **Compare state** — Detect drift between intended and observed state.
6. **Choose action** — Restart, stop, diagnose or refuse from evidence.

## Decision Point

The key decision point is whether REQ-004 has enough resolved context to authorize the next action. The control plane evaluates required facts, relevant evidence and blocking unknowns. A decision is not an intuition score; it is a transition with a reason.

If all mandatory inputs for the next step are present, the task can move forward. If a required ownership fact, runtime fact, dependency fact or verification requirement is missing, the transition is blocked and the next action becomes targeted gap resolution. This is the point where the system demonstrates control: it can refuse a technically possible action because the engineering preconditions are not satisfied.

The benefit is explainability. A reviewer can reconstruct what was known, what was missing and why the system chose to execute, retrieve, diagnose or stop.

## Implementation Shape

The implementation shape is intentionally compositional rather than monolithic. Collectors gather process, port, health and log metadata. A normalized snapshot preserves both intended and observed state. Drift evaluation emits reason codes instead of free-form guesses. Higher-level planning consumes structured snapshots rather than raw shell output.

The portfolio branch currently represents these statements as **DESIGN_INTENT** rather than pretending the complete platform is already proven. That distinction is part of the implementation discipline. When the corresponding source code, tests and runtime evidence are connected, individual claims can move to VERIFIED without changing the article structure.

The practical code pattern is to keep the orchestration contract stable while allowing capability providers to evolve. Configuration carries values that change across environments; typed task/evidence contracts carry semantics; adapters isolate process, filesystem, network, database or AI-specific behavior. No renderer, worker or prompt should need to know an arbitrary fixed number of steps, cards or articles.

This also keeps failure handling local. A collector can fail to resolve a process without changing the article writer. A knowledge lookup can return UNKNOWN without forcing the planner to invent a value. A verifier can block completion without re-running the entire workflow. Those boundaries make the system easier to debug because each stage has explicit input, output and status.

## Design Trade-offs

The architecture gains explicit state, bounded authority, resumability, better diagnostics, auditable evidence and safer AI participation. It also costs more than a single open-ended agent loop: there are contracts to model, state transitions to persist, evidence to classify, and configuration to maintain.

That cost should be paid selectively. Deterministic operations should remain deterministic; AI should be introduced where semantic interpretation is useful rather than because it is fashionable. A small task may need only a profile and one validator. A complex task may need knowledge retrieval, planning, execution, simulation and evidence gates. The same model should scale by composition, not by forcing every task through every engine.

The other trade-off is speed versus confidence. Blocking on missing context can feel slower than acting immediately. But when the missing fact can change the correct action, the apparent speed of guessing is borrowed time that will be repaid during debugging.

## Failure / Stop Path

A useful control plane must have a designed failure path for REQ-004. Suppose the first action fails, or the expected evidence does not appear. The system records the attempt, output and reason. It does not silently reset state and repeat the same hypothesis until something turns green.

A bounded retry is allowed only when the failure category indicates that repetition can change the outcome—for example a transient process-start race with a defined retry policy. If the same assertion fails again, the next action changes from retry to diagnosis. If required evidence remains UNKNOWN, completion remains blocked. If observed runtime contradicts intended state, the system resolves the drift before proceeding.

This turns failure into information. The task history shows which hypothesis was tested, what happened, and why the system stopped. A human can resume from that state rather than reconstructing the entire debugging conversation.

## Task Snapshot — After

**TASK:** REQ-004
**GOAL:** Restore the expected API runtime
**KNOWN:** process owner, artifact identity, port map, health, log provenance, drift
**UNKNOWN:** root cause only if evidence still conflicts
**CONFIDENCE:** 0.94
**NEXT CONTROL ACTION:** Apply the smallest evidence-backed corrective action

## Verification / Evidence

| Claim | Evidence | Verdict |
|---|---|---|
| Runtime context distinguishes intended state from observed state | runtime snapshot model | DESIGN_INTENT |
| Deterministic drift rules can operate without an LLM | drift evaluator | DESIGN_INTENT |
| Observations carry timestamp and source provenance | observation model | DESIGN_INTENT |

## What Is Actually Proven?

### DESIGN_INTENT
- Runtime context distinguishes intended state from observed state
- Deterministic drift rules can operate without an LLM
- Observations carry timestamp and source provenance

No claim in this draft is promoted beyond the evidence available to this portfolio branch.

## Architecture Reference

In the broader architecture, Runtime Context Engine is one capability inside a control loop rather than the whole system. The task enters through an orchestration boundary, context is resolved, actions are authorized, workers execute typed capabilities, and evidence flows back into verification. The article intentionally shows only the components that affect REQ-004; the full architecture is reference material, not the narrative starting point.

That ordering matters. A reader should first understand what happened to one task and why. Only then does the larger architecture map become useful as a way to locate the responsibility demonstrated in the story.

## Where This Pattern Appears Elsewhere

Infrastructure reconciliation loops, observability systems, service supervisors and configuration-drift tools all distinguish desired state from observed state before remediation.

For **REQ-004**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate a governed engineering decision?” but “what state must exist before a governed engineering decision is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## One Line to Remember

Configuration is intended state. Runtime context is observed state. Safe automation needs both.

## What Comes Next

Article 5 connects runtime observations with durable engineering knowledge through the Knowledge Spine.

## References / Provenance

- Repository: `Yashu7372/whatsapp-bot`
- Portfolio branch: `feature/portfolio-content-studio`
- Article workspace: `portfolio/articles/004` conceptually mapped to `ECP-004`
- Proof status in this draft: design intent unless source evidence explicitly states otherwise
- Incident source: generalized engineering scenario
