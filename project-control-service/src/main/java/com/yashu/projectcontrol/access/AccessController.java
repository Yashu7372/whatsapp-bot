package com.yashu.projectcontrol.access;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/access")
public class AccessController {

    private final ProjectAccessService accessService;

    public AccessController(ProjectAccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping
    public AccessView access(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID scopeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        UUID userId = principal.userId();
        var context = accessService.resolveActor(userId, projectId, scopeId);
        Map<String, DecisionView> decisions = new LinkedHashMap<>();
        Arrays.stream(ProjectAccessService.AccessAction.values()).forEach(action -> {
            var decision = accessService.decide(userId, action, projectId, scopeId);
            decisions.put(action.name(), new DecisionView(decision.outcome().name(), decision.reason()));
        });
        return new AccessView(
                userId, principal.displayName(), projectId, scopeId,
                context.workspaceRoles(), context.scopeAssignments(), decisions);
    }

    @GetMapping("/workflow-options")
    public ProjectAccessService.WorkflowConfigurationOptions workflowOptions(
            @PathVariable UUID projectId,
            @RequestParam UUID scopeId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return accessService.workflowConfigurationOptions(principal.userId(), projectId, scopeId);
    }

    public record DecisionView(String outcome, String reason) {}

    public record AccessView(
            UUID userId,
            String displayName,
            UUID projectId,
            UUID scopeId,
            java.util.List<String> workspaceRoles,
            java.util.List<ActorContext.ScopeAssignment> scopeAssignments,
            Map<String, DecisionView> decisions) {}
}
