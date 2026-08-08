package com.whatsappbot.document;

import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentService documentService;
    private final DocumentAuditService documentAuditService;
    private final FeatureAccessService featureAccessService;
    private final ProjectAccessService projectAccessService;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final DocumentAuthorizationService documentAuthorizationService;
    private final ApprovalDecisionCoordinator approvalDecisionCoordinator;
    private final EncryptedDocumentMetadataValidator encryptedMetadataValidator;

    @PostMapping(consumes="multipart/form-data")
    public ResponseEntity<DocumentResponse> create(@AuthenticationPrincipal Claims claims,@RequestPart("title") String title,
            @RequestPart(value="docType",required=false) String docType,@RequestPart(value="description",required=false) String description,
            @RequestPart(value="projectId",required=false) String projectId,@RequestPart(value="file",required=false) MultipartFile file) throws IOException{
        UUID tenantId=tenantId(claims),userId=userId(claims);assertDocumentAccess(tenantId);projectAccessService.requireActiveUser(tenantId,userId);
        UUID parsed=projectId!=null&&!projectId.isBlank()?UUID.fromString(projectId):null;
        if(parsed!=null)projectAuthorizationService.require(tenantId,userId,parsed,ProjectPermission.DOCUMENT_CREATE);
        return ResponseEntity.ok(toResponse(documentService.createDocument(tenantId,userId,new DocumentService.CreateDocumentRequest(title,docType,description,null,parsed),file)));
    }
    @PostMapping(value="/encrypted",consumes="multipart/form-data")
    public ResponseEntity<DocumentResponse> createEncrypted(@AuthenticationPrincipal Claims claims,@RequestPart("metadata") String metadataJson,
            @RequestPart(value="encryptedFile",required=false) MultipartFile encryptedFile)throws IOException{
        UUID tenantId=tenantId(claims),userId=userId(claims);assertDocumentAccess(tenantId);featureAccessService.assertFeatureEnabled(tenantId,FeatureCode.ZERO_KNOWLEDGE_STORAGE);
        projectAccessService.requireActiveUser(tenantId,userId);encryptedMetadataValidator.validate(metadataJson,encryptedFile);
        return ResponseEntity.ok(toResponse(documentService.createEncryptedDocument(tenantId,userId,metadataJson,encryptedFile)));
    }
    @GetMapping("/{id}/audit")
    public ResponseEntity<List<DocumentAuditService.AuditEventView>> auditTrail(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireView(t,u,id);return ResponseEntity.ok(documentAuditService.getAuditTrail(t,id));}
    @PostMapping(consumes="application/json")
    public ResponseEntity<DocumentResponse> createJson(@AuthenticationPrincipal Claims claims,@RequestBody DocumentService.CreateDocumentRequest req)throws IOException{UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);projectAccessService.requireActiveUser(t,u);if(req.projectId()!=null)projectAuthorizationService.require(t,u,req.projectId(),ProjectPermission.DOCUMENT_CREATE);return ResponseEntity.ok(toResponse(documentService.createDocument(t,u,req,null)));}
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(@AuthenticationPrincipal Claims claims,@RequestParam(required=false) String docType){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);return ResponseEntity.ok(documentService.listDocuments(t,u,docType).stream().filter(d->documentAuthorizationService.canView(t,u,d.getId())).map(this::toResponse).toList());}
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireView(t,u,id);return ResponseEntity.ok(toResponse(documentService.getDocument(t,u,id)));}
    @PutMapping(value="/{id}",consumes="multipart/form-data")
    public ResponseEntity<DocumentResponse> update(@AuthenticationPrincipal Claims claims,@PathVariable UUID id,@RequestPart(value="title",required=false) String title,
            @RequestPart(value="description",required=false) String description,@RequestPart(value="changeNotes",required=false) String changeNotes,@RequestPart(value="file",required=false) MultipartFile file)throws IOException{UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireEdit(t,u,id);return ResponseEntity.ok(toResponse(documentService.updateDocument(t,u,id,new DocumentService.UpdateDocumentRequest(title,description,null,changeNotes),file)));}
    @PatchMapping(value="/{id}",consumes="application/json")
    public ResponseEntity<DocumentResponse> patch(@AuthenticationPrincipal Claims claims,@PathVariable UUID id,@RequestBody DocumentService.UpdateDocumentRequest req)throws IOException{UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireEdit(t,u,id);return ResponseEntity.ok(toResponse(documentService.updateDocument(t,u,id,req,null)));}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireEdit(t,u,id);documentService.deleteDocument(t,id);return ResponseEntity.noContent().build();}
    @GetMapping("/{id}/versions")
    public ResponseEntity<List<VersionResponse>> versions(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireView(t,u,id);return ResponseEntity.ok(documentService.listVersions(t,id).stream().map(v->new VersionResponse(v.getId(),v.getDocumentId(),v.getVersionNum(),v.getRevisionCode(),v.getIssueStatus(),v.getIssuePurpose(),v.getAssetId(),v.getChangeNotes(),v.getIssuedAt(),v.getCreatedAt())).toList());}
    @GetMapping("/{id}/comments")
    public ResponseEntity<List<CommentResponse>> comments(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireView(t,u,id);return ResponseEntity.ok(documentService.listComments(t,id).stream().map(c->new CommentResponse(c.getId(),c.getDocumentId(),c.getAuthor()!=null?c.getAuthor().getFullName():"Unknown",c.getBody(),c.getCreatedAt())).toList());}
    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentResponse> addComment(@AuthenticationPrincipal Claims claims,@PathVariable UUID id,@RequestBody CommentRequest req){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireView(t,u,id);var c=documentService.addComment(t,u,id,req.body());return ResponseEntity.ok(new CommentResponse(c.getId(),c.getDocumentId(),c.getAuthor()!=null?c.getAuthor().getFullName():"Unknown",c.getBody(),c.getCreatedAt()));}
    @PostMapping("/{id}/submit")
    public ResponseEntity<ApprovalResponse> submit(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireEdit(t,u,id);return ResponseEntity.ok(toApprovalResponse(documentService.submitForApproval(t,u,id)));}
    @GetMapping("/{id}/approvals")
    public ResponseEntity<List<ApprovalResponse>> approvals(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);documentAuthorizationService.requireView(t,u,id);return ResponseEntity.ok(documentService.listApprovals(t,id).stream().map(this::toApprovalResponse).toList());}
    @PostMapping("/approvals/{approvalId}/decide")
    public ResponseEntity<Void> decide(@AuthenticationPrincipal Claims claims,@PathVariable UUID approvalId,@RequestBody DecisionRequest req){UUID t=tenantId(claims),u=userId(claims);assertDocumentAccess(t);approvalDecisionCoordinator.decide(t,u,approvalId,req.decision(),req.comments(),req.reviewOutcome());return ResponseEntity.ok().build();}

    private void assertDocumentAccess(UUID t){featureAccessService.assertAccess(t,FeatureCode.DOCUMENT_CONTROL);}
    private static UUID tenantId(Claims c){return UUID.fromString((String)c.get("tenantId"));}
    private static UUID userId(Claims c){return UUID.fromString(c.getSubject());}
    private DocumentResponse toResponse(DocumentEntity d){return new DocumentResponse(d.getId(),d.getTitle(),d.getDocType(),d.getDescription(),d.getTags(),d.getCurrentVersion(),d.getCurrentRevisionCode(),d.getStatus().name(),d.getWorkflowId(),d.getProjectId(),d.getOriginatorOrgId(),d.getDocumentCode(),d.getSecurityClassification(),d.getDiscipline(),d.getPackageCode(),d.getLocationCode(),d.getIssuePurpose(),d.getDueAt(),d.getIssuedAt(),d.getReviewOutcome(),d.getCreatedAt(),d.getUpdatedAt());}
    private ApprovalResponse toApprovalResponse(DocumentApprovalEntity a){return new ApprovalResponse(a.getId(),a.getDocumentId(),a.getStatus(),a.getCurrentStep(),a.getStartedAt(),a.getCompletedAt());}
    public record DocumentResponse(UUID id,String title,String docType,String description,String[] tags,int currentVersion,String currentRevisionCode,String status,UUID workflowId,UUID projectId,UUID originatorOrgId,String documentCode,String securityClassification,String discipline,String packageCode,String locationCode,String issuePurpose,LocalDateTime dueAt,LocalDateTime issuedAt,ReviewOutcome reviewOutcome,LocalDateTime createdAt,LocalDateTime updatedAt){}
    public record VersionResponse(UUID id,UUID documentId,int versionNum,String revisionCode,String issueStatus,String issuePurpose,UUID assetId,String changeNotes,LocalDateTime issuedAt,LocalDateTime createdAt){}
    public record CommentResponse(UUID id,UUID documentId,String authorName,String body,LocalDateTime createdAt){}
    public record ApprovalResponse(UUID id,UUID documentId,String status,int currentStep,LocalDateTime startedAt,LocalDateTime completedAt){}
    public record CommentRequest(String body){}
    public record DecisionRequest(String decision,String comments,ReviewOutcome reviewOutcome){}
}
