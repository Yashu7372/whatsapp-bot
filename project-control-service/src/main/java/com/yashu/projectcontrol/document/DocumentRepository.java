package com.yashu.projectcontrol.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    List<Document> findByProjectIdOrderByCreatedAtAsc(UUID projectId);
    Optional<Document> findByIdAndProjectId(UUID id, UUID projectId);
    boolean existsByProjectIdAndDocumentNumberIgnoreCase(UUID projectId, String documentNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> lockById(@Param("id") UUID id);
}
