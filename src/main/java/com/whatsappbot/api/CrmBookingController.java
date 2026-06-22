package com.whatsappbot.api;

import com.whatsappbot.domain.appointment.ServiceAppointmentEntity;
import com.whatsappbot.domain.appointment.ServiceAppointmentRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/bookings")
@RequiredArgsConstructor
public class CrmBookingController {

    private final ServiceAppointmentRepository appointmentRepository;

    @GetMapping
    public ResponseEntity<List<BookingResponse>> list(
            @AuthenticationPrincipal Claims claims,
            @RequestParam(required = false) String status) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        List<ServiceAppointmentEntity> appts;
        if (status != null && !status.isBlank()) {
            appts = appointmentRepository.findAllByTenantIdAndStatusOrderByAppointmentDateDescTimeSlotAsc(
                    tenantId, status.toUpperCase());
        } else {
            appts = appointmentRepository.findAllByTenantIdOrderByAppointmentDateDescTimeSlotAsc(tenantId);
        }
        return ResponseEntity.ok(appts.stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        ServiceAppointmentEntity appt = appointmentRepository.findById(id)
                .filter(a -> a.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + id));
        return ResponseEntity.ok(toResponse(appt));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<BookingResponse> updateStatus(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody StatusRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        ServiceAppointmentEntity appt = appointmentRepository.findById(id)
                .filter(a -> a.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + id));
        appt.setStatus(req.status().toUpperCase());
        return ResponseEntity.ok(toResponse(appointmentRepository.save(appt)));
    }

    private BookingResponse toResponse(ServiceAppointmentEntity a) {
        String contactName = a.getContact() != null ? a.getContact().getDisplayName() : null;
        String phone = a.getContact() != null ? a.getContact().getPhoneNumber() : null;
        return new BookingResponse(a.getId(), contactName, phone, a.getServiceType(),
                a.getAppointmentDate(), a.getTimeSlot(), a.getStatus(), a.getNotes());
    }

    public record BookingResponse(UUID id, String contactName, String phoneNumber,
                                   String serviceType, LocalDate appointmentDate,
                                   String timeSlot, String status, String notes) {}

    public record StatusRequest(String status) {}
}
