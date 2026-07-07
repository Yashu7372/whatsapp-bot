package com.whatsappbot.api;

import com.whatsappbot.domain.appointment.ServiceAppointmentRepository;
import com.whatsappbot.domain.contact.ContactRepository;
import com.whatsappbot.domain.conversation.ConversationRepository;
import com.whatsappbot.domain.message.MessageDirection;
import com.whatsappbot.domain.message.MessageRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/stats")
@RequiredArgsConstructor
public class DashboardStatsController {

    private final ContactRepository contactRepository;
    private final ConversationRepository conversationRepository;
    private final ServiceAppointmentRepository appointmentRepository;
    private final MessageRepository messageRepository;

    @GetMapping
    public ResponseEntity<StatsResponse> stats(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));

        long totalContacts = contactRepository.findAll().stream()
                .filter(c -> c.getTenant().getId().equals(tenantId))
                .count();

        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();

        long leadsToday = conversationRepository.findAll().stream()
                .filter(c -> c.getTenant().getId().equals(tenantId))
                .filter(c -> c.getCreatedAt() != null && !c.getCreatedAt().isBefore(startOfDay))
                .count();

        long messagesSent = messageRepository.countByTenantIdAndDirection(tenantId, MessageDirection.OUTBOUND);

        long totalBookings = appointmentRepository
                .findAllByTenantIdOrderByAppointmentDateDescTimeSlotAsc(tenantId)
                .stream()
                .filter(a -> !"AVAILABLE".equalsIgnoreCase(a.getStatus()))
                .count();

        return ResponseEntity.ok(new StatsResponse(
                leadsToday, messagesSent, totalContacts, totalBookings
        ));
    }

    public record StatsResponse(long leadsToday,
                                long messagesSent,
                                long totalContacts,
                                long totalBookings) {}
}
