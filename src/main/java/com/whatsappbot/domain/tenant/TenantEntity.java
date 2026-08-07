package com.whatsappbot.domain.tenant;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tenants")
public class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_code", nullable = false, unique = true, length = 100)
    private String tenantCode;

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_type", nullable = false, length = 50)
    private BusinessType businessType;

    @Column(name = "phone_number_id", nullable = false, unique = true, length = 100)
    private String phoneNumberId;

    @Column(name = "waba_id", length = 100)
    private String wabaId;

    @Column(name = "access_token_encrypted")
    private String accessTokenEncrypted;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "text")
    private String systemPrompt;

    @Column(name = "default_language", nullable = false, length = 20)
    private String defaultLanguage = "en";

    @Column(name = "timezone", nullable = false, length = 100)
    private String timezone = "Asia/Dubai";

    @Column(name = "business_hours", nullable = false, length = 200)
    private String businessHours = "Sat-Thu 9am-9pm";

    @Column(name = "crm_business_type", nullable = false, length = 50)
    private String crmBusinessType = "other";

    @Column(name = "whatsapp_number", length = 100)
    private String whatsappNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "faq_json", nullable = false, columnDefinition = "jsonb")
    private String faqJson = "[]";

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
