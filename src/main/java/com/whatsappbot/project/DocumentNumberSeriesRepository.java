package com.whatsappbot.project;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentNumberSeriesRepository extends JpaRepository<DocumentNumberSeriesEntity, UUID> {

    List<DocumentNumberSeriesEntity> findAllByTenantIdAndProjectId(UUID tenantId, UUID projectId);

    Optional<DocumentNumberSeriesEntity> findByTenantIdAndProjectIdAndDocType(UUID tenantId,
                                                                              UUID projectId,
                                                                              String docType);

    /**
     * Takes a row lock before issuing the next reference.
     *
     * <p>Two people creating an RFI at the same moment would otherwise both read the same
     * {@code next_number} and produce duplicate references. The unique index on
     * {@code (project_id, document_code)} would reject the second, so the lock is what turns a
     * failed request into a correctly numbered one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM DocumentNumberSeriesEntity s
            WHERE s.tenant.id = :tenantId AND s.projectId = :projectId AND s.docType = :docType
            """)
    Optional<DocumentNumberSeriesEntity> lockForUpdate(@Param("tenantId") UUID tenantId,
                                                        @Param("projectId") UUID projectId,
                                                        @Param("docType") String docType);
}
