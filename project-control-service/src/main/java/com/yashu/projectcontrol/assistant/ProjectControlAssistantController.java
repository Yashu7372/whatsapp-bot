package com.yashu.projectcontrol.assistant;

import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only assistant surface shared by web and future channel adapters such as WhatsApp.
 * Approval/rejection still uses the deterministic workflow action endpoint.
 */
@RestController
@RequestMapping("/api/v1/workflow-instances/{workflowInstanceId}/assistant")
public class ProjectControlAssistantController {

    private final ProjectControlAssistantService service;

    public ProjectControlAssistantController(ProjectControlAssistantService service) {
        this.service = service;
    }

    @GetMapping("/reviewer-brief")
    public ProjectControlAssistantService.ReviewerBrief reviewerBrief(
            @PathVariable UUID workflowInstanceId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return service.reviewerBrief(principal.userId(), workflowInstanceId);
    }

    @PostMapping("/query")
    public ProjectControlAssistantService.AssistantAnswer query(
            @PathVariable UUID workflowInstanceId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @Valid @RequestBody AssistantQuery request) {
        return service.answer(principal.userId(), workflowInstanceId, request.question());
    }

    public record AssistantQuery(@NotBlank String question) {}
}
