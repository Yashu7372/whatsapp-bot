package com.whatsappbot.content;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentVariantRepository extends JpaRepository<ContentVariantEntity, UUID> {
    List<ContentVariantEntity> findAllByContentIdeaId(UUID contentIdeaId);
}
