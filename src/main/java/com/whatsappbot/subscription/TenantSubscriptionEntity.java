package com.whatsappbot.subscription;

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
@Table(name = "tenant_subscriptions")
public class TenantSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "plan_code", nullable = false, length = 100)
    private String planCode;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "TRIAL";

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "trial_ends_at")
    private LocalDateTime trialEndsAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        startedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
