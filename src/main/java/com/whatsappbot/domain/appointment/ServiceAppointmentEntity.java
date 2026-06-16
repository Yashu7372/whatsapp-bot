package com.whatsappbot.domain.appointment;

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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "service_appointments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_service_appointments_slot", columnNames = {"tenant_id", "appointment_date", "time_slot"})
        },
        indexes = {
                @Index(name = "idx_service_appointments_tenant_date_status", columnList = "tenant_id, appointment_date, status"),
                @Index(name = "idx_service_appointments_contact", columnList = "contact_id"),
                @Index(name = "idx_service_appointments_vehicle", columnList = "vehicle_id")
        }
)
public class ServiceAppointmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private ContactEntity contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private VehicleEntity vehicle;

    @Column(name = "service_type", nullable = false, length = 100)
    private String serviceType = "ANY";

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "time_slot", nullable = false, length = 30)
    private String timeSlot;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "AVAILABLE";

    @Column(name = "customer_phone", length = 50)
    private String customerPhone;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isAvailable() {
        return "AVAILABLE".equalsIgnoreCase(status);
    }

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
