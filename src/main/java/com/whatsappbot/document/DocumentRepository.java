package com.whatsappbot.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findAllByTenantIdOrderByUpdatedAtDesc(UUID tenantId);

    List<DocumentEntity> findAllByTenantIdAndDocTypeOrderByUpdatedAtDesc(UUID tenantId, String docType);

    Optional<DocumentEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Takes a write lock on the document row.
     *
     * <p>Used as the serialisation point when appending to the document's audit chain: two
     * concurrent events would otherwise read the same previous hash and fork the chain.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DocumentEntity d WHERE d.id = :id")
    Optional<DocumentEntity> lockById(@Param("id") UUID id);
}
