package com.yashu.projectcontrol.intelligence;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Domain modules publish small post-state-change signals through this boundary.
 * Collectors execute only after the authoritative transaction commits.
 */
@Component
public class ProjectIntelligenceSignalPublisher {

    private final ApplicationEventPublisher events;

    public ProjectIntelligenceSignalPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publish(ProjectIntelligenceSignal signal) {
        events.publishEvent(signal);
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
