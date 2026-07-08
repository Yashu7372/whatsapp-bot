package com.whatsappbot.api;

import com.whatsappbot.application.conversation.ConversationService;
import com.whatsappbot.application.livechat.LiveChatService;
import com.whatsappbot.domain.agent.TenantAgent;
import com.whatsappbot.domain.agent.TenantAgentRepository;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.conversation.ConversationRepository;
import com.whatsappbot.domain.conversation.ConversationStatus;
import com.whatsappbot.domain.message.Message;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/conversations")
@RequiredArgsConstructor
public class CrmConversationController {

    private final ConversationRepository conversationRepository;
    private final TenantRepository tenantRepository;
    private final ConversationService conversationService;
    private final WhatsAppGraphClient whatsAppGraphClient;
    private final TenantAgentRepository tenantAgentRepository;
    private final LiveChatService liveChatService;

    @GetMapping
    public ResponseEntity<List<ConversationResponse>> list(@AuthenticationPrincipal Claims claims) {
        TenantEntity tenant = getTenant(claims);
        Map<UUID, String> agentNames = agentNamesByTenant(tenant);
        List<ConversationResponse> result = conversationRepository.findAllByTenantOrderByLastMessageAtDesc(tenant)
                .stream()
                .map(c -> toResponse(c, agentNames))
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        TenantEntity tenant = getTenant(claims);
        ConversationEntity conv = getConversation(tenant, id);
        return ResponseEntity.ok(toResponse(conv, agentNamesByTenant(tenant)));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MessageResponse>> messages(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit) {

        ConversationEntity conversation = getConversation(getTenant(claims), id);
        conversationService.clearUnreadCount(conversation);

        // recentMessages returns newest-first; the UI renders oldest-first.
        // Stream.toList() is immutable, so copy before reversing.
        List<MessageResponse> messages = new java.util.ArrayList<>(conversationService.recentMessages(id, limit)
                .stream()
                .map(this::toMessageResponse)
                .toList());
        java.util.Collections.reverse(messages);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/{id}/send")
    public ResponseEntity<MessageResponse> send(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody SendMessageRequest request) {

        TenantEntity tenant = getTenant(claims);
        ConversationEntity conversation = getConversation(tenant, id);
        String messageText = request.message() != null ? request.message().trim() : "";
        if (messageText.isBlank()) {
            throw new IllegalArgumentException("Message cannot be blank");
        }

        String phoneNumber = conversation.getContact() != null ? conversation.getContact().getPhoneNumber() : null;
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("Conversation contact phone number is missing");
        }

        whatsAppGraphClient.sendTextMessage(tenant, phoneNumber, messageText);
        Message saved = conversationService.saveAgentOutbound(tenant, conversation, messageText, conversation.getAssignedAgentId());
        return ResponseEntity.ok(toMessageResponse(saved));
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<ConversationResponse> assign(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody AssignAgentRequest request) {

        TenantEntity tenant = getTenant(claims);
        if (request.agentId() == null) {
            throw new IllegalArgumentException("agentId is required");
        }
        liveChatService.assign(tenant, id, request.agentId(), request.notes());
        // Re-fetch with the contact joined — the entity returned by the service
        // leaves its transaction, so its lazy contact can't be read here.
        return ResponseEntity.ok(toResponse(getConversation(tenant, id), agentNamesByTenant(tenant)));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<ConversationResponse> updateStatus(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody UpdateStatusRequest request) {

        TenantEntity tenant = getTenant(claims);
        ConversationEntity conversation = getConversation(tenant, id);
        String status = request.status() == null ? "" : request.status().trim().toLowerCase();
        switch (status) {
            case "bot" -> {
                conversation.setStatus(ConversationStatus.ACTIVE);
                conversation.setBotEnabled(true);
                conversation.setAssignedAgentId(null);
            }
            case "human" -> {
                conversation.setStatus(ConversationStatus.INTERVENE);
                conversation.setBotEnabled(false);
            }
            case "closed" -> {
                conversation.setStatus(ConversationStatus.RESOLVED);
                conversation.setBotEnabled(false);
            }
            default -> throw new IllegalArgumentException("Unsupported conversation status: " + request.status());
        }
        conversationRepository.save(conversation);
        // Respond from the fetch-joined instance — save() merges into a new
        // managed copy whose lazy contact can't be read once the transaction ends.
        return ResponseEntity.ok(toResponse(conversation, agentNamesByTenant(tenant)));
    }

    private ConversationResponse toResponse(ConversationEntity c, Map<UUID, String> agentNames) {
        String contactName = c.getContact() != null ? c.getContact().getDisplayName() : null;
        String phone = c.getContact() != null ? c.getContact().getPhoneNumber() : null;
        String waId = c.getContact() != null ? c.getContact().getWaId() : null;
        String language = c.getContact() != null ? c.getContact().getLanguage() : "en";
        UUID assignedAgentId = c.getAssignedAgentId();
        return new ConversationResponse(
                c.getId(),
                contactName,
                phone,
                waId,
                language,
                toUiStatus(c),
                c.getPriority().name(),
                c.isBotEnabled(),
                c.getUnreadCount(),
                c.getLastMessagePreview(),
                c.getLastMessageAt(),
                assignedAgentId,
                assignedAgentId != null ? agentNames.get(assignedAgentId) : null
        );
    }

    private Map<UUID, String> agentNamesByTenant(TenantEntity tenant) {
        return tenantAgentRepository.findByTenantAndActiveTrueOrderByNameAsc(tenant)
                .stream()
                .collect(java.util.stream.Collectors.toMap(TenantAgent::getId, TenantAgent::getName, (a, b) -> a));
    }

    private MessageResponse toMessageResponse(Message m) {
        return new MessageResponse(
                m.getId(),
                m.getConversation().getId(),
                m.getDirection().name().toLowerCase(),
                m.getTextBody(),
                m.getCreatedAt(),
                m.getIntent(),
                m.getConfidenceScore(),
                m.getActionType(),
                m.getButtonsJson(),
                m.isAiGenerated()
        );
    }

    private TenantEntity getTenant(Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
    }

    private ConversationEntity getConversation(TenantEntity tenant, UUID id) {
        return conversationRepository.findByIdAndTenantWithContact(id, tenant)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + id));
    }

    private String toUiStatus(ConversationEntity conversation) {
        if (conversation.getStatus() == ConversationStatus.RESOLVED) {
            return "closed";
        }
        return conversation.isBotEnabled() ? "bot" : "human";
    }

    public record ConversationResponse(UUID id,
                                       String contactName,
                                       String contactPhone,
                                       String waId,
                                       String language,
                                       String status,
                                       String priority,
                                       boolean botEnabled,
                                       int unreadCount,
                                       String lastMessage,
                                       LocalDateTime lastMessageAt,
                                       UUID assignedAgentId,
                                       String assignedAgentName) {}

    public record MessageResponse(UUID id,
                                  UUID conversationId,
                                  String direction,
                                  String content,
                                  LocalDateTime sentAt,
                                  String intent,
                                  Double confidenceScore,
                                  String actionType,
                                  String buttons,
                                  boolean aiGenerated) {}

    public record SendMessageRequest(String message) {}

    public record UpdateStatusRequest(String status) {}

    public record AssignAgentRequest(UUID agentId, String notes) {}
}
