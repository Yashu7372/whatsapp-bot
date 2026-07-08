package com.whatsappbot.domain.conversation;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {
    Optional<ConversationEntity> findByTenantAndContact(TenantEntity tenant, ContactEntity contact);

    // Contact is fetched eagerly here because the CRM controllers read it
    // after the transaction closes (open-in-view is disabled) — a lazy proxy
    // would throw LazyInitializationException.
    @Query("select c from ConversationEntity c join fetch c.contact " +
           "where c.tenant = :tenant order by c.lastMessageAt desc")
    List<ConversationEntity> findAllByTenantOrderByLastMessageAtDesc(@Param("tenant") TenantEntity tenant);

    List<ConversationEntity> findAllByTenantOrderByLastMessageAtDesc(TenantEntity tenant, Pageable pageable);

    @Query("select c from ConversationEntity c join fetch c.contact " +
           "where c.id = :id and c.tenant = :tenant")
    Optional<ConversationEntity> findByIdAndTenantWithContact(@Param("id") UUID id, @Param("tenant") TenantEntity tenant);
}
