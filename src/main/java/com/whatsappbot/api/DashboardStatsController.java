package com.whatsappbot.api;

import com.whatsappbot.domain.appointment.ServiceAppointmentRepository;
import com.whatsappbot.domain.contact.ContactRepository;
import com.whatsappbot.domain.conversation.ConversationRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/stats")
@RequiredArgsConstructor
public class DashboardStatsController {

    private final ContactRepository contactRepository;
    private final ConversationRepository conversationRepository;
    private final ServiceAppointmentRepository appointmentRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<StatsResponse> stats(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        long totalContacts = contactRepository.findAll().stream()
                .filter(c -> c.getTenant().getId().equals(tenantId)).count();

        long totalConversations = conversationRepository.findAll().stream()
                .filter(c -> c.getTenant().getId().equals(tenantId)).count();

        long todayBookings = appointmentRepository
                .findAllByTenantIdOrderByAppointmentDateDescTimeSlotAsc(tenantId)
                .stream()
                .filter(a -> a.getAppointmentDate().equals(LocalDate.now()))
                .count();

        long pendingBookings = appointmentRepository
                .findAllByTenantIdAndStatusOrderByAppointmentDateDescTimeSlotAsc(tenantId, "BOOKED")
                .size();

        return ResponseEntity.ok(new StatsResponse(
                totalContacts, totalConversations, todayBookings, pendingBookings,
                tenant.getBusinessName(), tenant.getBusinessType().name()
        ));
    }

    public record StatsResponse(long totalContacts, long totalConversations,
                                 long todayBookings, long pendingBookings,
                                 String businessName, String businessType) {}
}
