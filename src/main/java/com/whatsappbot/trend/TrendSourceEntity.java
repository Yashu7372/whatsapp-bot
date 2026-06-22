package com.whatsappbot.trend;

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
@Table(name = "trend_sources")
public class TrendSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 50)
    private TrendSourceType sourceType;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static TrendSourceEntity create(TenantEntity tenant, String name, TrendSourceType sourceType) {
        TrendSourceEntity source = new TrendSourceEntity();
        source.setTenant(tenant);
        source.setName(name);
        source.setSourceType(sourceType);
        source.setActive(true);
        source.setMetadata("{}");
        return source;
    }

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
