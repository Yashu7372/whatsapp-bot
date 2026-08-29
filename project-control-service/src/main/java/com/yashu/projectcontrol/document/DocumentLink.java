package com.yashu.projectcontrol.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_links")
public class DocumentLink {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "revision_id")
    private UUID revisionId;

    @Column(name = "relationship_type", nullable = false, length = 80)
    private String relationshipType;

    @Column(name = "target_type", nullable = false, length = 100)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "target_reference", length = 240)
    private String targetReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentLink() {
    }

    private DocumentLink(UUID id, UUID projectId, UUID documentId, UUID revisionId,
                         String relationshipType, String targetType, UUID targetId,
                         String targetReference, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.documentId = documentId;
        this.revisionId = revisionId;
        this.relationshipType = relationshipType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetReference = targetReference;
        this.createdAt = createdAt;
    }

    static DocumentLink create(UUID projectId, UUID documentId, UUID revisionId,
                               String relationshipType, String targetType, UUID targetId,
                               String targetReference) {
        return new DocumentLink(UUID.randomUUID(), projectId, documentId, revisionId,
                relationshipType, targetType, targetId, targetReference, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getDocumentId() { return documentId; }
    public UUID getRevisionId() { return revisionId; }
    public String getRelationshipType() { return relationshipType; }
    public String getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public String getTargetReference() { return targetReference; }
    public Instant getCreatedAt() { return createdAt; }
}
