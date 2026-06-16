package com.whatsappbot.domain.appointment;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.vehicle.VehicleEntity;
import jakarta.persistence.*;
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
@Table(name = "service_appointments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_service_appointments_slot", columnNames = {"tenant_id", "appointment_date", "time_slot"})
}, indexes = {
        @Index(name = "idx_service_appointments_tenant_status", columnList = "tenant_id, status, appointment_date"),
        @Index(name = "idx_service_appointments_customer", columnList = "tenant_id, customer_phone")
})
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

    @Column(name = "service_type", nullable = false, length = 120)
    private String serviceType;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate appointmentDate;

    @Column(name = "time_slot", nullable = false, length = 30)
    private String timeSlot;

    @Column(nullable = false, length = 50)
    private String status = "AVAILABLE";

    @Column(name = "customer_phone", length = 50)
    private String customerPhone;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(columnDefinition = "text")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isAvailable() {
        return "AVAILABLE".equalsIgnoreCase(status);
    }

    public void book(ContactEntity contact,
                     VehicleEntity vehicle,
                     String serviceType,
                     String customerPhone,
                     String customerName,
                     String notes) {
        this.contact = contact;
        this.vehicle = vehicle;
        this.serviceType = serviceType;
        this.customerPhone = customerPhone;
        this.customerName = customerName;
        this.notes = notes;
        this.status = "BOOKED";
    }

    public void cancel() {
        this.status = "CANCELLED";
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
