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
@Table(name = "document_revisions")
public class DocumentRevision {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "revision_code", nullable = false, length = 40)
    private String revisionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "revision_status", nullable = false, length = 32)
    private RevisionStatus revisionStatus;

    @Column(name = "change_notes", columnDefinition = "text")
    private String changeNotes;

    @Column(name = "content_uri", length = 1000)
    private String contentUri;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(name = "media_type", length = 160)
    private String mediaType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentRevision() {
    }

    private DocumentRevision(UUID id, UUID documentId, UUID projectId, int sequenceNumber,
                             String revisionCode, RevisionStatus revisionStatus, String changeNotes,
                             String contentUri, String contentSha256, String originalFilename,
                             String mediaType, Long sizeBytes, Instant createdAt) {
        this.id = id;
        this.documentId = documentId;
        this.projectId = projectId;
        this.sequenceNumber = sequenceNumber;
        this.revisionCode = revisionCode;
        this.revisionStatus = revisionStatus;
        this.changeNotes = changeNotes;
        this.contentUri = contentUri;
        this.contentSha256 = contentSha256;
        this.originalFilename = originalFilename;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
    }

    static DocumentRevision create(UUID documentId, UUID projectId, int sequenceNumber, String revisionCode,
                                   String changeNotes, String contentUri, String contentSha256,
                                   String originalFilename, String mediaType, Long sizeBytes) {
        return new DocumentRevision(UUID.randomUUID(), documentId, projectId, sequenceNumber, revisionCode,
                RevisionStatus.DRAFT, changeNotes, contentUri, contentSha256, originalFilename,
                mediaType, sizeBytes, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getProjectId() { return projectId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public String getRevisionCode() { return revisionCode; }
    public RevisionStatus getRevisionStatus() { return revisionStatus; }
    public String getChangeNotes() { return changeNotes; }
    public String getContentUri() { return contentUri; }
    public String getContentSha256() { return contentSha256; }
    public String getOriginalFilename() { return originalFilename; }
    public String getMediaType() { return mediaType; }
    public Long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
}
