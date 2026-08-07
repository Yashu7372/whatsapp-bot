package com.whatsappbot.domain.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    boolean existsByWaMessageId(String waMessageId);

    @Query("SELECT m FROM Message m WHERE m.conversation.id = :conversationId ORDER BY m.createdAt DESC")
    List<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    long countByTenantIdAndDirection(UUID tenantId, MessageDirection direction);

    long countByTenantIdAndDirectionAndCreatedAtGreaterThanEqual(UUID tenantId, MessageDirection direction, java.time.LocalDateTime createdAt);
}
