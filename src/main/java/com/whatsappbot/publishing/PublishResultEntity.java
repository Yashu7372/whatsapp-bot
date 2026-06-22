package com.whatsappbot.publishing;

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
@Table(name = "publish_results")
public class PublishResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "external_publish_id", length = 300)
    private String externalPublishId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static PublishResultEntity ok(TenantEntity tenant, UUID jobId, String externalPublishId) {
        PublishResultEntity result = new PublishResultEntity();
        result.setTenant(tenant);
        result.setJobId(jobId);
        result.setSuccess(true);
        result.setExternalPublishId(externalPublishId);
        result.setPublishedAt(LocalDateTime.now());
        return result;
    }

    public static PublishResultEntity failed(TenantEntity tenant, UUID jobId, String errorMessage) {
        PublishResultEntity result = new PublishResultEntity();
        result.setTenant(tenant);
        result.setJobId(jobId);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
