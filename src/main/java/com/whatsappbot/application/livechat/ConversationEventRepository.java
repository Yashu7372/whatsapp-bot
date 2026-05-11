package com.whatsappbot.application.livechat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConversationEventRepository extends JpaRepository<ConversationEvent, UUID> {
}
