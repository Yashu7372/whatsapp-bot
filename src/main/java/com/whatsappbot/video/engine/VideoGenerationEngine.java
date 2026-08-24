package com.whatsappbot.video.engine;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Small orchestration engine: execute capabilities, collect artifacts, run gates,
 * then advance the state. It contains no provider-specific logic.
 */
@Service
public class VideoGenerationEngine {

    private final List<GenerationAdapter> adapters;
    private final List<GenerationGate> gates;
    private final List<PipelineStep> pipeline = PipelineStep.defaultPipeline();

    public VideoGenerationEngine(List<GenerationAdapter> adapters, List<GenerationGate> gates) {
        this.adapters = adapters == null ? List.of() : adapters.stream()
                .sorted(Comparator.comparingInt(GenerationAdapter::priority))
                .toList();
        this.gates = gates == null ? List.of() : List.copyOf(gates);
    }

    public ExecutionResult executeNext(GenerationState currentState, GenerationContext context) {
        if (currentState == null) {
            throw new IllegalArgumentException("currentState is required");
        }
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (currentState.terminal()) {
            return new ExecutionResult(currentState, context, List.of(), List.of("Workflow is already terminal."));
        }

        PipelineStep step = pipeline.stream()
                .filter(candidate -> candidate.from() == currentState)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No pipeline step configured from " + currentState));

        GenerationContext working = context;
        List<String> messages = new ArrayList<>();

        for (GenerationCapability capability : step.requiredCapabilities()) {
            if (shouldSkip(capability, working)) {
                messages.add("Skipped " + capability + " for mode " + working.mode());
                continue;
            }
            GenerationAdapter adapter = selectRequiredAdapter(capability, working);
            StageResult result = adapter.generate(working);
            working = working.withArtifacts(result.artifacts());
            messages.add(adapter.name() + ": " + result.message());
        }

        for (GenerationCapability capability : step.optionalCapabilities()) {
            if (shouldSkip(capability, working)) {
                continue;
            }
            selectAdapter(capability, working).ifPresent(adapter -> {
                // Optional capability execution is handled below to preserve immutability.
            });
            GenerationAdapter optional = selectAdapter(capability, working).orElse(null);
            if (optional != null) {
                StageResult result = optional.generate(working);
                working = working.withArtifacts(result.artifacts());
                messages.add(optional.name() + ": " + result.message());
            }
        }

        List<GateResult> gateResults = new ArrayList<>();
        List<GenerationGate> applicableGates = gates.stream()
                .filter(gate -> gate.supports(step.to(), working))
                .toList();
        if (applicableGates.isEmpty()) {
            throw new IllegalStateException("No gate configured for target state " + step.to());
        }

        for (GenerationGate gate : applicableGates) {
            GateResult result = gate.validate(step.to(), working);
            gateResults.add(result);
            if (!result.passed()) {
                throw new GateRejectedException(step.to(), gate.name(), result);
            }
        }

        return new ExecutionResult(step.to(), working, gateResults, messages);
    }

    private GenerationAdapter selectRequiredAdapter(GenerationCapability capability, GenerationContext context) {
        return selectAdapter(capability, context)
                .orElseThrow(() -> new IllegalStateException(
                        "No generation adapter is available for capability " + capability + " and mode " + context.mode()));
    }

    private java.util.Optional<GenerationAdapter> selectAdapter(
            GenerationCapability capability,
            GenerationContext context
    ) {
        return adapters.stream()
                .filter(adapter -> adapter.capability() == capability)
                .filter(adapter -> adapter.supports(context))
                .findFirst();
    }

    private boolean shouldSkip(GenerationCapability capability, GenerationContext context) {
        return context.mode() == GenerationMode.FACELESS
                && (capability == GenerationCapability.PRESENTER || capability == GenerationCapability.LIP_SYNC);
    }

    public record ExecutionResult(
            GenerationState state,
            GenerationContext context,
            List<GateResult> gateResults,
            List<String> messages
    ) {
        public ExecutionResult {
            gateResults = gateResults == null ? List.of() : List.copyOf(gateResults);
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
