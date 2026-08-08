package com.whatsappbot.document;

import com.whatsappbot.project.ProjectAccessService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-workflows")
@RequiredArgsConstructor
public class DocumentWorkflowController {

    private final DocumentService documentService;
    private final ProjectAccessService accessService;
    private final WorkflowDefinitionValidator validator;

    @PostMapping
    public ResponseEntity<WorkflowResponse> create(@AuthenticationPrincipal Claims claims,
                                                    @RequestBody DocumentService.CreateWorkflowRequest req) {
        UUID tenantId=tenantId(claims),userId=userId(claims);
        requireWorkflowAdministrator(tenantId,userId);
        validator.validate(tenantId,req.steps());
        return ResponseEntity.ok(toResponse(documentService.createWorkflow(tenantId,req)));
    }

    @GetMapping
    public ResponseEntity<List<WorkflowResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId=tenantId(claims),userId=userId(claims);
        accessService.requireActiveUser(tenantId,userId);
        return ResponseEntity.ok(documentService.listWorkflows(tenantId).stream().map(this::toResponse).toList());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<WorkflowResponse> update(@AuthenticationPrincipal Claims claims,@PathVariable UUID id,
                                                    @RequestBody DocumentService.UpdateWorkflowRequest req) {
        UUID tenantId=tenantId(claims),userId=userId(claims);
        requireWorkflowAdministrator(tenantId,userId);
        if(req.steps()!=null) validator.validate(tenantId,req.steps());
        return ResponseEntity.ok(toResponse(documentService.updateWorkflow(tenantId,id,req)));
    }

    private void requireWorkflowAdministrator(UUID tenantId,UUID userId){
        var actor=accessService.requireActiveUser(tenantId,userId);
        if(!accessService.isTenantAdministrator(actor))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only a true tenant administrator can configure workflow templates");
    }
    private static UUID tenantId(Claims c){return UUID.fromString((String)c.get("tenantId"));}
    private static UUID userId(Claims c){return UUID.fromString(c.getSubject());}
    private WorkflowResponse toResponse(DocumentControlWorkflowEntity w){return new WorkflowResponse(w.getId(),w.getName(),w.getDocType(),w.getSteps(),w.isActive(),w.getCreatedAt(),w.getUpdatedAt());}

    public record WorkflowResponse(UUID id,String name,String docType,String steps,boolean active,LocalDateTime createdAt,LocalDateTime updatedAt){}
}
