package com.whatsappbot.payment;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One line of a payment claim, tied to the document that evidences the work.
 *
 * <p>The document link is what makes the claim checkable: an amount can only be certified once
 * the document proving the work was approved.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_application_items")
public class PaymentApplicationItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "payment_application_id", nullable = false)
    private UUID paymentApplicationId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
