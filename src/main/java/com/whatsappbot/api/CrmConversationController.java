package com.whatsappbot.api;

import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.conversation.ConversationRepository;
import com.whatsappbot.domain.message.MessageRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/conversations")
@RequiredArgsConstructor
public class CrmConversationController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        List<ConversationResponse> result = conversationRepository.findAll(
                        Sort.by(Sort.Direction.DESC, "lastMessageAt"))
                .stream()
                .filter(c -> c.getTenant().getId().equals(tenantId))
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        ConversationEntity conv = conversationRepository.findById(id)
                .filter(c -> c.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
        return ResponseEntity.ok(toResponse(conv));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> messages(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        conversationRepository.findById(id)
                .filter(c -> c.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));

        List<MessageResponse> messages = messageRepository
                .findByConversationIdOrderByCreatedAtDesc(id, PageRequest.of(0, limit))
                .stream()
                .map(m -> new MessageResponse(m.getId(), m.getConversation().getId(),
                        m.getDirection().name(), m.getTextBody(), m.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(messages);
    }

    private ConversationResponse toResponse(ConversationEntity c) {
        String contactName = c.getContact() != null ? c.getContact().getDisplayName() : null;
        String phone = c.getContact() != null ? c.getContact().getPhoneNumber() : null;
        return new ConversationResponse(c.getId(), contactName, phone,
                c.getStatus().name(), c.getPriority().name(), c.isBotEnabled(), c.getLastMessageAt());
    }

    public record ConversationResponse(UUID id, String contactName, String phoneNumber,
                                        String status, String priority, boolean botEnabled,
                                        LocalDateTime lastMessageAt) {}

    public record MessageResponse(UUID id, UUID conversationId, String direction,
                                   String body, LocalDateTime sentAt) {}
}
