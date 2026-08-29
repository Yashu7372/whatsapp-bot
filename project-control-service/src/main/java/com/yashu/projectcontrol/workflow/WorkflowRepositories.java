package com.yashu.projectcontrol.workflow;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {
    boolean existsByProjectIdAndCodeIgnoreCaseAndVersion(UUID projectId, String code, int version);
    Optional<WorkflowDefinition> findByIdAndProjectId(UUID id, UUID projectId);
    List<WorkflowDefinition> findByProjectIdOrderByCodeAscVersionAsc(UUID projectId);
}

interface WorkflowStepDefinitionRepository extends JpaRepository<WorkflowStepDefinition, UUID> {
    boolean existsByWorkflowDefinitionIdAndStepSequence(UUID workflowDefinitionId, int stepSequence);
    boolean existsByWorkflowDefinitionIdAndStepCodeIgnoreCase(UUID workflowDefinitionId, String stepCode);
    List<WorkflowStepDefinition> findByWorkflowDefinitionIdOrderByStepSequenceAsc(UUID workflowDefinitionId);
    Optional<WorkflowStepDefinition> findByWorkflowDefinitionIdAndStepSequence(UUID workflowDefinitionId, int stepSequence);
    Optional<WorkflowStepDefinition> findByWorkflowDefinitionIdAndStepCodeIgnoreCase(UUID workflowDefinitionId, String stepCode);
}

interface ScopeWorkflowBindingRepository extends JpaRepository<ScopeWorkflowBinding, UUID> {
    Optional<ScopeWorkflowBinding> findByScopeIdAndWorkflowDefinitionId(UUID scopeId, UUID workflowDefinitionId);
    Optional<ScopeWorkflowBinding> findByScopeIdAndWorkflowDefinitionIdAndEnabledTrue(UUID scopeId, UUID workflowDefinitionId);
    List<ScopeWorkflowBinding> findByScopeIdOrderByCreatedAtAsc(UUID scopeId);
}

interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {
    boolean existsByProjectIdAndWorkflowDefinitionIdAndBusinessKeyIgnoreCase(
            UUID projectId, UUID workflowDefinitionId, String businessKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkflowInstance w where w.id = :id")
    Optional<WorkflowInstance> lockById(@Param("id") UUID id);
}

interface WorkflowStepInstanceRepository extends JpaRepository<WorkflowStepInstance, UUID> {
    Optional<WorkflowStepInstance> findByIdAndWorkflowInstanceId(UUID id, UUID workflowInstanceId);
    Optional<WorkflowStepInstance> findTopByWorkflowInstanceIdAndStepSequenceOrderByVisitNumberDesc(
            UUID workflowInstanceId, int stepSequence);
    List<WorkflowStepInstance> findByWorkflowInstanceIdOrderByActivatedAtAsc(UUID workflowInstanceId);
}

interface WorkflowActionRepository extends JpaRepository<WorkflowAction, UUID> {
    List<WorkflowAction> findByWorkflowInstanceIdOrderByCreatedAtAsc(UUID workflowInstanceId);
}
