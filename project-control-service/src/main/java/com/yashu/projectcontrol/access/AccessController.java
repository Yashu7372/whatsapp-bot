package com.yashu.projectcontrol.access;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/access")
public class AccessController {

    public static final String ACTOR_HEADER = "X-Project-Control-User";

    private final ProjectAccessService accessService;
    private final IdentityService identityService;

    public AccessController(ProjectAccessService accessService, IdentityService identityService) {
        this.accessService = accessService;
        this.identityService = identityService;
    }

    @GetMapping
    public AccessView access(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID scopeId,
            @RequestHeader(ACTOR_HEADER) UUID userId) {
        var user = identityService.getUser(userId);
        var context = accessService.resolveActor(userId, projectId, scopeId);
        Map<String, DecisionView> decisions = new LinkedHashMap<>();
        Arrays.stream(ProjectAccessService.AccessAction.values()).forEach(action -> {
            var decision = accessService.decide(userId, action, projectId, scopeId);
            decisions.put(action.name(), new DecisionView(decision.outcome().name(), decision.reason()));
        });
        return new AccessView(
                user.id(), user.displayName(), projectId, scopeId,
                context.workspaceRoles(), context.scopeAssignments(), decisions);
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
