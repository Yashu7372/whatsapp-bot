package com.whatsappbot.domain.template;

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
@Table(name = "whatsapp_message_templates", uniqueConstraints = {
        @UniqueConstraint(name = "uk_whatsapp_message_templates", columnNames = {"tenant_id", "template_code", "language_code"})
})
public class WhatsAppMessageTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "template_code", nullable = false, length = 120)
    private String templateCode;

    @Column(name = "meta_template_name", nullable = false, length = 250)
    private String metaTemplateName;

    @Column(name = "language_code", nullable = false, length = 20)
    private String languageCode = "en";

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private TemplateCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 50)
    private TemplateAudience audience = TemplateAudience.CUSTOMER;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "body_preview", columnDefinition = "text")
    private String bodyPreview;

    @Column(name = "component_schema_json", columnDefinition = "text")
    private String componentSchemaJson;

    @Column(name = "default_components_json", columnDefinition = "text")
    private String defaultComponentsJson;

    @Column(name = "enabled_for_ai", nullable = false)
    private boolean enabledForAi;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
