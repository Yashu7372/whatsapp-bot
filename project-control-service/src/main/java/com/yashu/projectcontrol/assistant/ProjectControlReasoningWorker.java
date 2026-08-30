package com.yashu.projectcontrol.assistant;

/**
 * Replaceable reasoning worker for Project Control.
 *
 * <p>The worker receives only an already-authorized, evidence-bearing context pack.
 * It has no repositories, workflow commands, approval tools or payment tools. Business
 * state changes remain in their deterministic application services.</p>
 */
public interface ProjectControlReasoningWorker {

    String reason(ReasoningRequest request);

    record ReasoningRequest(
            String task,
            String question,
            String authorizedContextJson) {
    }
}
