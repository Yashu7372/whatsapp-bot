package com.whatsappbot.domain.service;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.vehicle.VehicleEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "service_records",
        indexes = {
                @Index(name = "idx_service_records_vehicle_date", columnList = "vehicle_id, service_date"),
                @Index(name = "idx_service_records_contact_date", columnList = "contact_id, service_date"),
                @Index(name = "idx_service_records_tenant_type", columnList = "tenant_id, service_type")
        }
)
public class ServiceRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private VehicleEntity vehicle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contact_id", nullable = false)
    private ContactEntity contact;

    @Column(name = "service_type", nullable = false, length = 100)
    private String serviceType;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "technician_name", length = 150)
    private String technicianName;

    @Column(name = "mileage_at_service")
    private Integer mileageAtService;

    @Column(name = "cost", precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name = "currency", length = 10)
    private String currency = "AED";

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "service_date", nullable = false)
    private LocalDateTime serviceDate;

    @Column(name = "next_service_date")
    private LocalDateTime nextServiceDate;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "COMPLETED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

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
