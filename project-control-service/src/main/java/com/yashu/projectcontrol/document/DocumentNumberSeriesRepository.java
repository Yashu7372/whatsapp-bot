package com.yashu.projectcontrol.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentNumberSeriesRepository extends JpaRepository<DocumentNumberSeries, UUID> {

    Optional<DocumentNumberSeries> findByProjectIdAndDocumentType(UUID projectId, String documentType);

    List<DocumentNumberSeries> findByProjectIdOrderByDocumentTypeAsc(UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DocumentNumberSeries s where s.projectId = :projectId and s.documentType = :documentType")
    Optional<DocumentNumberSeries> lockForUpdate(@Param("projectId") UUID projectId,
                                                 @Param("documentType") String documentType);
}
