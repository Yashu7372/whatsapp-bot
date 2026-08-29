package com.yashu.projectcontrol.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_workflow_instances")
public class DocumentWorkflowLink {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "workflow_instance_id", nullable = false)
    private UUID workflowInstanceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentWorkflowLink() {
    }

    private DocumentWorkflowLink(UUID id, UUID documentId, UUID workflowInstanceId, Instant createdAt) {
        this.id = id;
        this.documentId = documentId;
        this.workflowInstanceId = workflowInstanceId;
        this.createdAt = createdAt;
    }

    static DocumentWorkflowLink create(UUID documentId, UUID workflowInstanceId) {
        return new DocumentWorkflowLink(UUID.randomUUID(), documentId, workflowInstanceId, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getWorkflowInstanceId() { return workflowInstanceId; }
    public Instant getCreatedAt() { return createdAt; }
}
