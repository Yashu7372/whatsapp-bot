package com.whatsappbot.domain.appointment;

import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceAppointmentRepository extends JpaRepository<ServiceAppointmentEntity, UUID> {
    List<ServiceAppointmentEntity> findTop10ByTenantAndAppointmentDateAndStatusOrderByTimeSlotAsc(TenantEntity tenant, LocalDate appointmentDate, String status);
    Optional<ServiceAppointmentEntity> findByTenantAndAppointmentDateAndTimeSlot(TenantEntity tenant, LocalDate appointmentDate, String timeSlot);
}
