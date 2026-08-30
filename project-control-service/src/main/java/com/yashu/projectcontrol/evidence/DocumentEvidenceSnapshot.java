package com.yashu.projectcontrol.evidence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_evidence_snapshots")
public class DocumentEvidenceSnapshot {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "extractor_code", nullable = false, length = 100)
    private String extractorCode;

    @Column(name = "extractor_version", nullable = false, length = 100)
    private String extractorVersion;

    @Column(name = "input_content_sha256", length = 64)
    private String inputContentSha256;

    @Column(name = "evidence_json", nullable = false, columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "created_by_reference", length = 200)
    private String createdByReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DocumentEvidenceSnapshot() {
    }

    private DocumentEvidenceSnapshot(
            UUID id,
            UUID projectId,
            UUID documentId,
            UUID revisionId,
            String extractorCode,
            String extractorVersion,
            String inputContentSha256,
            String evidenceJson,
            String createdByReference,
            Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.documentId = documentId;
        this.revisionId = revisionId;
        this.extractorCode = extractorCode;
        this.extractorVersion = extractorVersion;
        this.inputContentSha256 = inputContentSha256;
        this.evidenceJson = evidenceJson;
        this.createdByReference = createdByReference;
        this.createdAt = createdAt;
    }

    static DocumentEvidenceSnapshot create(
            UUID projectId,
            UUID documentId,
            UUID revisionId,
            String extractorCode,
            String extractorVersion,
            String inputContentSha256,
            String evidenceJson,
            String createdByReference) {
        return new DocumentEvidenceSnapshot(
                UUID.randomUUID(), projectId, documentId, revisionId,
                extractorCode, extractorVersion, inputContentSha256,
                evidenceJson, createdByReference, Instant.now());
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getDocumentId() { return documentId; }
    public UUID getRevisionId() { return revisionId; }
    public String getExtractorCode() { return extractorCode; }
    public String getExtractorVersion() { return extractorVersion; }
    public String getInputContentSha256() { return inputContentSha256; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getCreatedByReference() { return createdByReference; }
    public Instant getCreatedAt() { return createdAt; }
}
