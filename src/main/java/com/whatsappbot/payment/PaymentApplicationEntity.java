package com.whatsappbot.payment;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.project.OrganizationEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A claim for payment covering a period of work.
 *
 * <p>The monetary fields are derived rather than entered: {@code grossClaimed} is the sum of the
 * linked items, retention is taken from the project's contract rate, and the net follows from
 * both. Storing them keeps the figures as they stood when the claim was certified, which is the
 * point of the record — recomputing historic claims from today's rates would rewrite history.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_applications")
public class PaymentApplicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "application_ref", nullable = false, length = 80)
    private String applicationRef;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claimed_by_org_id", nullable = false)
    private OrganizationEntity claimedByOrg;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "gross_claimed", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossClaimed = BigDecimal.ZERO;

    @Column(name = "previously_certified", nullable = false, precision = 18, scale = 2)
    private BigDecimal previouslyCertified = BigDecimal.ZERO;

    @Column(name = "retention_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal retentionPercent = BigDecimal.ZERO;

    @Column(name = "retention_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal retentionAmount = BigDecimal.ZERO;

    @Column(name = "net_certified", nullable = false, precision = 18, scale = 2)
    private BigDecimal netCertified = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PaymentApplicationStatus status = PaymentApplicationStatus.DRAFT;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certified_by")
    private TenantUserEntity certifiedBy;

    @Column(name = "certified_at")
    private LocalDateTime certifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private TenantUserEntity createdBy;

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
