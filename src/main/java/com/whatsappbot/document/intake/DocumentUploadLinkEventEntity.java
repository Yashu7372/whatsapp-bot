package com.whatsappbot.document.intake;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "document_upload_link_events")
public class DocumentUploadLinkEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "link_id", nullable = false)
    private UUID linkId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "uploader_name", length = 255)
    private String uploaderName;

    @Column(name = "uploader_email", length = 320)
    private String uploaderEmail;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
