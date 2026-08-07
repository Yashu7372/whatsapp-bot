package com.whatsappbot.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentApprovalRepository extends JpaRepository<DocumentApprovalEntity, UUID> {

    // Tenant-scoped: document ids are guessable, and an approval history names its reviewers.
    List<DocumentApprovalEntity> findAllByTenantIdAndDocumentIdOrderByStartedAtDesc(UUID tenantId,
                                                                                    UUID documentId);

    Optional<DocumentApprovalEntity> findFirstByTenantIdAndDocumentIdAndStatusOrderByStartedAtDesc(
            UUID tenantId, UUID documentId, String status);

    /**
     * Takes a write lock on the approval row before a decision is applied.
     *
     * <p>Deciding a step is a read-modify-write over the approval's current position. Two
     * reviewers acting at the same moment would both read step N, both write a decision, and the
     * approval could advance once while recording two decisions — or complete twice. Since an
     * approval is now the precondition for claiming payment, that race has a financial
     * consequence, so the row is locked for the duration of the decision.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM DocumentApprovalEntity a WHERE a.id = :id AND a.tenant.id = :tenantId")
    Optional<DocumentApprovalEntity> lockByIdAndTenantId(@Param("id") UUID id,
                                                          @Param("tenantId") UUID tenantId);
}
