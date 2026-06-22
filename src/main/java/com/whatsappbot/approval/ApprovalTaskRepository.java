package com.whatsappbot.approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalTaskRepository extends JpaRepository<ApprovalTaskEntity, UUID> {
    List<ApprovalTaskEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<ApprovalTaskEntity> findAllByTenantIdAndStatus(UUID tenantId, ApprovalStatus status);
    Optional<ApprovalTaskEntity> findByContentIdeaId(UUID contentIdeaId);
}
