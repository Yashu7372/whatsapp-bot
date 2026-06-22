package com.whatsappbot.content;

import com.whatsappbot.domain.tenant.TenantEntity;
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
@Table(name = "content_variants")
public class ContentVariantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "content_idea_id", nullable = false)
    private UUID contentIdeaId;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    @Column(name = "hashtags", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] hashtags;

    @Column(name = "call_to_action", length = 500)
    private String callToAction;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static ContentVariantEntity create(TenantEntity tenant, UUID contentIdeaId, String body,
                                              String[] hashtags, String callToAction, int version) {
        ContentVariantEntity variant = new ContentVariantEntity();
        variant.setTenant(tenant);
        variant.setContentIdeaId(contentIdeaId);
        variant.setBody(body);
        variant.setHashtags(hashtags);
        variant.setCallToAction(callToAction);
        variant.setVersion(version);
        return variant;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
