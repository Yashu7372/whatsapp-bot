package com.yashu.projectcontrol.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "primary_scope_id")
    private UUID primaryScopeId;

    @Column(name = "originator_organization_id")
    private UUID originatorOrganizationId;

    @Column(name = "document_number", nullable = false, length = 180)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "number_source", nullable = false, length = 32)
    private DocumentNumberSource numberSource;

    @Column(name = "number_series_code", length = 100)
    private String numberSeriesCode;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "classification_code", length = 80)
    private String classificationCode;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    private String metadataJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DocumentStatus status;

    @Column(name = "current_revision_sequence", nullable = false)
    private int currentRevisionSequence;

    @Column(name = "current_revision_code", length = 40)
    private String currentRevisionCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
    }

    private Document(UUID id, UUID projectId, UUID primaryScopeId, UUID originatorOrganizationId,
                     String documentNumber, DocumentNumberSource numberSource, String numberSeriesCode,
                     String documentType, String title, String description, String classificationCode,
                     String metadataJson, DocumentStatus status, int currentRevisionSequence,
                     String currentRevisionCode, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.primaryScopeId = primaryScopeId;
        this.originatorOrganizationId = originatorOrganizationId;
        this.documentNumber = documentNumber;
        this.numberSource = numberSource;
        this.numberSeriesCode = numberSeriesCode;
        this.documentType = documentType;
        this.title = title;
        this.description = description;
        this.classificationCode = classificationCode;
        this.metadataJson = metadataJson;
        this.status = status;
        this.currentRevisionSequence = currentRevisionSequence;
        this.currentRevisionCode = currentRevisionCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static Document create(UUID projectId, UUID primaryScopeId, UUID originatorOrganizationId,
                           String documentNumber, DocumentNumberSource numberSource, String numberSeriesCode,
                           String documentType, String title, String description, String classificationCode,
                           String metadataJson) {
        Instant now = Instant.now();
        return new Document(UUID.randomUUID(), projectId, primaryScopeId, originatorOrganizationId,
                documentNumber, numberSource, numberSeriesCode, documentType, title, description,
                classificationCode, metadataJson, DocumentStatus.DRAFT, 0, null, now, now);
    }

    void advanceRevision(int sequence, String revisionCode) {
        if (sequence != currentRevisionSequence + 1) {
            throw new IllegalArgumentException("Revision sequence must advance exactly once");
        }
        currentRevisionSequence = sequence;
        currentRevisionCode = revisionCode;
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getPrimaryScopeId() { return primaryScopeId; }
    public UUID getOriginatorOrganizationId() { return originatorOrganizationId; }
    public String getDocumentNumber() { return documentNumber; }
    public DocumentNumberSource getNumberSource() { return numberSource; }
    public String getNumberSeriesCode() { return numberSeriesCode; }
    public String getDocumentType() { return documentType; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getClassificationCode() { return classificationCode; }
    public String getMetadataJson() { return metadataJson; }
    public DocumentStatus getStatus() { return status; }
    public int getCurrentRevisionSequence() { return currentRevisionSequence; }
    public String getCurrentRevisionCode() { return currentRevisionCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
