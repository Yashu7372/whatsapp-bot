package com.whatsappbot.payment;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** One tamper-evident entry in a payment application's history. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_audit_events")
public class PaymentAuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "payment_application_id", nullable = false)
    private UUID paymentApplicationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private TenantUserEntity actorUser;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_payload", columnDefinition = "jsonb")
    private Map<String, Object> eventPayload;

    @Column(name = "event_hash", length = 128)
    private String eventHash;

    @Column(name = "previous_event_hash", length = 128)
    private String previousEventHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
