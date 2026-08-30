package com.yashu.projectcontrol.intelligence;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain modules publish small post-state-change signals through this boundary.
 * Collectors execute only after the authoritative transaction commits.
 */
@Component
public class ProjectIntelligenceSignalPublisher {

    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    public ProjectIntelligenceSignalPublisher(ApplicationEventPublisher events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    public void publish(ProjectIntelligenceSignal signal) {
        events.publishEvent(signal);
    }

    public void publish(
            UUID projectId,
            UUID scopeId,
            String triggerType,
            String subjectType,
            UUID subjectId,
            String triggerKey,
            Object payload,
            Instant occurredAt) {
        publish(new ProjectIntelligenceSignal(
                projectId,
                scopeId,
                triggerType,
                subjectType,
                subjectId,
                triggerKey,
                payloadJson(payload),
                occurredAt));
    }

    private String payloadJson(Object payload) {
        if (payload == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not serialize Project Intelligence signal payload", ex);
        }
    }
}

@Component
class ProjectIntelligenceSignalListener {

    private final ProjectIntelligenceCoordinator coordinator;

    ProjectIntelligenceSignalListener(ProjectIntelligenceCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void on(ProjectIntelligenceSignal signal) {
        coordinator.accept(signal);
    }
}
