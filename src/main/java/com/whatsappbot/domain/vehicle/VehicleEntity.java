package com.whatsappbot.domain.vehicle;

import com.whatsappbot.domain.contact.ContactEntity;
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
@Table(name = "vehicles", uniqueConstraints = {
        @UniqueConstraint(name = "uk_vehicles_tenant_plate", columnNames = {"tenant_id", "plate_number"})
}, indexes = {
        @Index(name = "idx_vehicles_tenant_contact", columnList = "tenant_id, contact_id"),
        @Index(name = "idx_vehicles_tenant_active", columnList = "tenant_id, active")
})
public class VehicleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contact_id", nullable = false)
    private ContactEntity contact;

    @Column(nullable = false, length = 100)
    private String make;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "vehicle_year")
    private Integer year;

    @Column(name = "plate_number", nullable = false, length = 50)
    private String plateNumber;

    @Column(length = 80)
    private String vin;

    @Column(length = 50)
    private String color;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(nullable = false)
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
