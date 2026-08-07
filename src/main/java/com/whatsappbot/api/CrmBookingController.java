package com.whatsappbot.api;

import com.whatsappbot.domain.appointment.ServiceAppointmentEntity;
import com.whatsappbot.domain.appointment.ServiceAppointmentRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/crm/bookings")
@RequiredArgsConstructor
public class CrmBookingController {

    /** Separator used by the {@code time_slot} column's "HH:mm-HH:mm" range format. */
    private static final String SLOT_RANGE_SEPARATOR = "-";

    private final ServiceAppointmentRepository appointmentRepository;

    // toResponse() reads the LAZY contact association. open-in-view is false, so without
    // an active transaction these read endpoints throw LazyInitializationException as soon
    // as the tenant has any bookings.
    @Transactional(readOnly = true)
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
            appts = appointmentRepository.findAllByTenantIdOrderByAppointmentDateDescTimeSlotAsc(tenantId)
                    .stream()
                    .filter(a -> !"AVAILABLE".equalsIgnoreCase(a.getStatus()))
                    .toList();
        }
        return ResponseEntity.ok(appts.stream().map(this::toResponse).toList());
    }

    @Transactional(readOnly = true)
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

    @Transactional
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

        LocalTime start = parseSlotBoundary(a.getTimeSlot(), 0);
        LocalTime end = parseSlotBoundary(a.getTimeSlot(), 1);

        String scheduledAt = null;
        if (a.getAppointmentDate() != null && start != null) {
            scheduledAt = LocalDateTime.of(a.getAppointmentDate(), start).toString();
        }
        Integer durationMins = (start != null && end != null && end.isAfter(start))
                ? (int) Duration.between(start, end).toMinutes()
                : null;

        return new BookingResponse(a.getId(), contactName, phone, a.getServiceType(),
                scheduledAt, durationMins, a.getStatus(), a.getNotes());
    }

    /**
     * Reads one side of a {@code time_slot} value. Slots are stored as a range
     * ("09:00-10:00"), so parsing the column as a whole {@link LocalTime} throws
     * {@link java.time.format.DateTimeParseException} for every seeded row.
     *
     * <p>Returns null rather than throwing on anything unexpected: one malformed slot
     * must not turn the whole bookings list into a 500.
     *
     * @param index 0 for the start of the range, 1 for the end
     */
    private static LocalTime parseSlotBoundary(String timeSlot, int index) {
        if (timeSlot == null || timeSlot.isBlank()) {
            return null;
        }
        String[] parts = timeSlot.split(SLOT_RANGE_SEPARATOR);
        if (index >= parts.length) {
            return null;
        }
        try {
            return LocalTime.parse(parts[index].trim());
        } catch (DateTimeParseException e) {
            log.warn("Unparseable appointment time_slot value: '{}'", timeSlot);
            return null;
        }
    }

    public record BookingResponse(UUID id, String contactName, String phoneNumber,
                                   String serviceType, String scheduledAt,
                                   Integer durationMins, String status, String notes) {}

    public record StatusRequest(String status) {}
}
