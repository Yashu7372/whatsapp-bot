package com.whatsappbot.project;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The running reference number for one instrument type on one project.
 *
 * <p>Which instruments exist is data rather than code: a project defines a series for each type
 * its contract uses, so nothing here has to change to support a new document type.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_number_series")
public class DocumentNumberSeriesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "doc_type", nullable = false, length = 100)
    private String docType;

    @Column(name = "prefix", nullable = false, length = 30)
    private String prefix;

    @Column(name = "next_number", nullable = false)
    private int nextNumber = 1;

    @Column(name = "padding", nullable = false)
    private int padding = 4;

    /** Contractual reply window in days, used to set a document's due date on issue. */
    @Column(name = "response_days")
    private Integer responseDays;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
