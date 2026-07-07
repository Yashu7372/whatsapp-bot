package com.whatsappbot.domain.conversation;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {
    Optional<ConversationEntity> findByTenantAndContact(TenantEntity tenant, ContactEntity contact);

    List<ConversationEntity> findAllByTenantOrderByLastMessageAtDesc(TenantEntity tenant);

    List<ConversationEntity> findAllByTenantOrderByLastMessageAtDesc(TenantEntity tenant, Pageable pageable);
}
