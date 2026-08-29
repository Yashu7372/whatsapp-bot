package com.yashu.projectcontrol.document;

import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_START;

@Service
public class DocumentWorkflowService {

    private final DocumentService documentService;
    private final DocumentWorkflowLinkRepository repository;
    private final WorkflowService workflowService;
    private final ProjectAccessService accessService;

    public DocumentWorkflowService(
            DocumentService documentService,
            DocumentWorkflowLinkRepository repository,
            WorkflowService workflowService,
            ProjectAccessService accessService) {
        this.documentService = documentService;
        this.repository = repository;
        this.workflowService = workflowService;
        this.accessService = accessService;
    }

    @Transactional
    public WorkflowService.InstanceView startForDocument(
            UUID userId,
            UUID documentId,
            UUID workflowDefinitionId,
            String businessKey,
            String title,
            String contextJson) {
        var document = documentService.get(documentId);
        if (document.primaryScopeId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A document must have a project scope before a scoped workflow can be started");
        }
        accessService.require(userId, DOCUMENT_VIEW, document.projectId(), document.primaryScopeId());
        accessService.require(userId, WORKFLOW_START, document.projectId(), document.primaryScopeId());
        var instance = workflowService.start(
                document.projectId(),
                document.primaryScopeId(),
                workflowDefinitionId,
                businessKey,
                title,
                userId.toString(),
                contextJson);
        repository.save(DocumentWorkflowLink.create(documentId, instance.id()));
        return instance;
    }

    @Transactional(readOnly = true)
    public List<WorkflowService.InstanceView> listForDocument(UUID userId, UUID documentId) {
        var document = documentService.get(documentId);
        accessService.require(userId, DOCUMENT_VIEW, document.projectId(), document.primaryScopeId());
        return repository.findByDocumentIdOrderByCreatedAtAsc(documentId).stream()
                .map(link -> workflowService.getInstance(link.getWorkflowInstanceId()))
                .toList();
    }
}
