package com.yashu.projectcontrol.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_number_series")
public class DocumentNumberSeries {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "series_code", nullable = false, length = 100)
    private String seriesCode;

    @Column(name = "document_type", nullable = false, length = 100)
    private String documentType;

    @Column(nullable = false, length = 120)
    private String prefix;

    @Column(nullable = false, length = 5)
    private String separator;

    @Column(name = "next_number", nullable = false)
    private int nextNumber;

    @Column(nullable = false)
    private int padding;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentNumberSeries() {
    }

    private DocumentNumberSeries(UUID id, UUID projectId, String seriesCode, String documentType,
                                 String prefix, String separator, int nextNumber, int padding,
                                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.seriesCode = seriesCode;
        this.documentType = documentType;
        this.prefix = prefix;
        this.separator = separator;
        this.nextNumber = nextNumber;
        this.padding = padding;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    static DocumentNumberSeries create(UUID projectId, String seriesCode, String documentType,
                                       String prefix, String separator, int padding) {
        Instant now = Instant.now();
        return new DocumentNumberSeries(UUID.randomUUID(), projectId, seriesCode, documentType,
                prefix, separator, 1, padding, now, now);
    }

    void configure(String documentType, String prefix, String separator, int padding) {
        this.documentType = documentType;
        this.prefix = prefix;
        this.separator = separator;
        this.padding = padding;
        this.updatedAt = Instant.now();
    }

    int takeNextNumber() {
        int number = nextNumber;
        nextNumber++;
        updatedAt = Instant.now();
        return number;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getSeriesCode() { return seriesCode; }
    public String getDocumentType() { return documentType; }
    public String getPrefix() { return prefix; }
    public String getSeparator() { return separator; }
    public int getNextNumber() { return nextNumber; }
    public int getPadding() { return padding; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
