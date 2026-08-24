# Profiles Before Processes

**A repeatable engineering runtime starts with explicit environment intent.**

**Article 2 / 70 · Engineering Control Plane**

**Task:** REQ-002 — Start reports-service locally with the correct dependencies

**Thesis:** Do not start a process and then discover its environment. Resolve the environment, then authorize the process.

## Engineering Tension

The central tension is **Convenience ↔ Reproducibility**. Both sides are legitimate. Optimizing only for convenience produces one class of failure; optimizing only for reproducibility produces another. The engineering problem is to build a system that can move between them deliberately rather than letting whichever tool is currently executing make that trade-off implicitly.

For **REQ-002**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate the next step?” but “what state must exist before the next step is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## The Real Problem

Running a process is trivial; running the right artifact with the right environment, dependency mode, credentials, ports and working directory is the real engineering problem. Invisible machine state makes local development fast for the person who remembers everything and fragile for everyone else—including automation.

For **REQ-002**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate a corrective action?” but “what state must exist before a corrective action is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## Of Course This Happened

**Incident type: generalized engineering scenario.** The service starts, the port opens, and everyone celebrates. Ten minutes later the logs reveal it connected to yesterday’s database profile while the developer thought a simulator was active. The process was technically alive, which is comforting in roughly the same way that a smoke alarm with a working LED is comforting.

The failure is not “bad environment variables.” The deeper failure is that intended runtime state was never represented as a first-class contract before execution.

This scenario is used as a teaching device, not claimed as a documented production incident. Its value is that the failure mode is common, observable and directly related to the architectural tension in this article.

## Why the Naive Approach Fails

The naive implementation usually optimizes for the shortest path: accept the request, gather some context, perform an action, run a convenient check and declare completion. That shape is attractive because it compresses orchestration into one loop. It is also where assumptions become invisible. For REQ-002, a successful command is not proof that the requested engineering outcome has been achieved.

For **REQ-002**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate execution?” but “what state must exist before execution is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## The System Idea

The system idea in this article is the **Profile-driven Runtime**. Its responsibility is deliberately narrower than “be intelligent.” It creates a controlled boundary around the task: represent state explicitly, resolve only the context needed for the current decision, authorize bounded actions, preserve unknowns, and attach evidence to transitions. It does **not** magically know production truth, replace engineering judgment, or convert weak evidence into certainty.

For **REQ-002**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate system behavior?” but “what state must exist before system behavior is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## When Would You Actually Use This?

Use this pattern when the cost of an incorrect action is meaningful, when work crosses multiple tools or dependencies, when tasks need to resume or be audited, when AI participates in implementation, or when success depends on several independent checks. It is especially valuable for enterprise development, migration work, incident diagnosis, release automation and multi-step engineering workflows.

It is probably unnecessary for a disposable script whose correctness is obvious from one deterministic command. The goal is not to wrap every shell command in ceremony. The goal is to add control where uncertainty, side effects or verification cost justify it.

For REQ-002, the pattern is useful because the task contains at least one unknown that can materially change the correct next action. That unknown is part of the state, not an inconvenience to be hidden.

## One Concrete Task

**REQ-002 — Start reports-service locally with the correct dependencies**

The service needs an artifact path, environment variables, database connectivity and messaging behavior. The developer should not reconstruct that knowledge from memory every time.

## Task Snapshot — Before

**TASK:** REQ-002
**GOAL:** Start reports-service reproducibly
**KNOWN:** service artifact, target profile name
**UNKNOWN:** resolved secrets, database mode, broker mode, port conflicts
**CONFIDENCE:** 0.46
**NEXT CONTROL ACTION:** Resolve and validate the effective profile

## Bounded Context for This Task

The bounded context for REQ-002 contains only information that can change the next decision. That includes the task goal, the known facts listed above, the unresolved facts that can block execution, the relevant implementation boundary and the evidence needed for verification. It deliberately excludes unrelated repositories, historical documents and broad domain material unless a relationship demonstrates why they matter.

This is not merely a token-saving optimization. Bounded context improves authority. If every available fact is injected, the system can no longer explain why a particular fact influenced the decision. A task-scoped context can attach provenance and relevance to each item, making it possible to say: this fact is present because it establishes ownership; this observation is present because it determines runtime drift; this test is present because it proves a required behavior.

The unknown set is equally important. In this article, the system is allowed to stop when an unknown is necessary for the next transition. It should not widen retrieval indefinitely or replace the missing fact with model confidence.

## Request Journey

1. **Select profile** — Choose named runtime intent for the task.
2. **Resolve variables** — Apply defaults, overrides and protected references.
3. **Validate artifacts** — Confirm commands, files and working directories exist.
4. **Resolve dependencies** — Choose real or simulated providers.
5. **Start processes** — Launch only after preflight succeeds.
6. **Capture runtime** — Persist effective non-secret configuration and process identity.

## Decision Point

The key decision point is whether REQ-002 has enough resolved context to authorize the next action. The control plane evaluates required facts, relevant evidence and blocking unknowns. A decision is not an intuition score; it is a transition with a reason.

If all mandatory inputs for the next step are present, the task can move forward. If a required ownership fact, runtime fact, dependency fact or verification requirement is missing, the transition is blocked and the next action becomes targeted gap resolution. This is the point where the system demonstrates control: it can refuse a technically possible action because the engineering preconditions are not satisfied.

The benefit is explainability. A reviewer can reconstruct what was known, what was missing and why the system chose to execute, retrieve, diagnose or stop.

## Implementation Shape

The implementation shape is intentionally compositional rather than monolithic. Profiles declare commands, artifacts, working directories, ports and required variables. Mutable/environment-specific values are configuration, not hardcoded control logic. Secret values remain external references. Preflight executes before child processes are created.

The portfolio branch currently represents these statements as **DESIGN_INTENT** rather than pretending the complete platform is already proven. That distinction is part of the implementation discipline. When the corresponding source code, tests and runtime evidence are connected, individual claims can move to VERIFIED without changing the article structure.

The practical code pattern is to keep the orchestration contract stable while allowing capability providers to evolve. Configuration carries values that change across environments; typed task/evidence contracts carry semantics; adapters isolate process, filesystem, network, database or AI-specific behavior. No renderer, worker or prompt should need to know an arbitrary fixed number of steps, cards or articles.

This also keeps failure handling local. A collector can fail to resolve a process without changing the article writer. A knowledge lookup can return UNKNOWN without forcing the planner to invent a value. A verifier can block completion without re-running the entire workflow. Those boundaries make the system easier to debug because each stage has explicit input, output and status.

## Design Trade-offs

The architecture gains explicit state, bounded authority, resumability, better diagnostics, auditable evidence and safer AI participation. It also costs more than a single open-ended agent loop: there are contracts to model, state transitions to persist, evidence to classify, and configuration to maintain.

That cost should be paid selectively. Deterministic operations should remain deterministic; AI should be introduced where semantic interpretation is useful rather than because it is fashionable. A small task may need only a profile and one validator. A complex task may need knowledge retrieval, planning, execution, simulation and evidence gates. The same model should scale by composition, not by forcing every task through every engine.

The other trade-off is speed versus confidence. Blocking on missing context can feel slower than acting immediately. But when the missing fact can change the correct action, the apparent speed of guessing is borrowed time that will be repaid during debugging.

## Failure / Stop Path

A useful control plane must have a designed failure path for REQ-002. Suppose the first action fails, or the expected evidence does not appear. The system records the attempt, output and reason. It does not silently reset state and repeat the same hypothesis until something turns green.

A bounded retry is allowed only when the failure category indicates that repetition can change the outcome—for example a transient process-start race with a defined retry policy. If the same assertion fails again, the next action changes from retry to diagnosis. If required evidence remains UNKNOWN, completion remains blocked. If observed runtime contradicts intended state, the system resolves the drift before proceeding.

This turns failure into information. The task history shows which hypothesis was tested, what happened, and why the system stopped. A human can resume from that state rather than reconstructing the entire debugging conversation.

## Task Snapshot — After

**TASK:** REQ-002
**GOAL:** Start reports-service reproducibly
**KNOWN:** artifact, effective profile, dependency modes, validated ports, log location
**UNKNOWN:** runtime health until observed
**CONFIDENCE:** 0.91
**NEXT CONTROL ACTION:** Launch and capture observed runtime state

## Verification / Evidence

| Claim | Evidence | Verdict |
|---|---|---|
| Profile-driven startup makes runtime intent explicit before process launch | profile schema | DESIGN_INTENT |
| Preflight validation can block missing artifacts and unresolved required variables | runtime validator | DESIGN_INTENT |
| Secrets are referenced rather than embedded in durable profiles | secret reference contract | DESIGN_INTENT |

## What Is Actually Proven?

### DESIGN_INTENT
- Profile-driven startup makes runtime intent explicit before process launch
- Preflight validation can block missing artifacts and unresolved required variables
- Secrets are referenced rather than embedded in durable profiles

No claim in this draft is promoted beyond the evidence available to this portfolio branch.

## Architecture Reference

In the broader architecture, Profile-driven Runtime is one capability inside a control loop rather than the whole system. The task enters through an orchestration boundary, context is resolved, actions are authorized, workers execute typed capabilities, and evidence flows back into verification. The article intentionally shows only the components that affect REQ-002; the full architecture is reference material, not the narrative starting point.

That ordering matters. A reader should first understand what happened to one task and why. Only then does the larger architecture map become useful as a way to locate the responsibility demonstrated in the story.

## Where This Pattern Appears Elsewhere

Twelve-factor configuration, Kubernetes manifests, Terraform plans, build profiles and deployment descriptors all reflect the same principle: declare intended environment before running workload behavior.

For **REQ-002**, this matters because the task is not an abstract architecture exercise. The system has to decide what it knows, what it does not know, which boundary owns the next action, and what evidence would justify moving forward. The useful design question is therefore not “can we automate a governed engineering decision?” but “what state must exist before a governed engineering decision is authorized, and how will a reviewer reconstruct that decision later?” That distinction keeps the article anchored to engineering behavior instead of turning it into a catalogue of boxes and arrows.

The control-plane view also forces a separation between **fact**, **interpretation**, and **verdict**. Facts such as branch identity, process state, configured dependencies, test output or known relationships should be collected deterministically. Interpretation can use AI where ambiguity genuinely exists. The final verdict returns to deterministic rules: missing required evidence remains missing, an unresolved ownership question remains unresolved, and a failed gate does not become success because the narrative sounds convincing.

## One Line to Remember

Do not start a process and then discover its environment. Resolve the environment, then authorize the process.

## What Comes Next

Article 3 uses the same profile model to swap unavailable enterprise infrastructure for deterministic simulation.

## References / Provenance

- Repository: `Yashu7372/whatsapp-bot`
- Portfolio branch: `feature/portfolio-content-studio`
- Article workspace: `portfolio/articles/002` conceptually mapped to `ECP-002`
- Proof status in this draft: design intent unless source evidence explicitly states otherwise
- Incident source: generalized engineering scenario
