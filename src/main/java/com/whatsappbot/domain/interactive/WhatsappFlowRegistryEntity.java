package com.whatsappbot.domain.interactive;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "whatsapp_flow_registry", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "flow_key"}))
public class WhatsappFlowRegistryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id")
    private TenantEntity tenant;

    @Column(name = "flow_key", nullable = false, length = 120)
    private String flowKey;

    @Column(name = "flow_id", nullable = false, length = 120)
    private String flowId;

    @Column(name = "flow_cta", nullable = false, length = 80)
    private String flowCta = "Open";

    @Column(name = "screen_id", length = 120)
    private String screenId;

    @Column(columnDefinition = "text")
    private String description;

    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}
