package com.whatsappbot.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UploadTokenRepository extends JpaRepository<UploadTokenEntity, UUID> {

    Optional<UploadTokenEntity> findByTokenAndUsedFalse(String token);

    @Modifying
    @Query("DELETE FROM UploadTokenEntity t WHERE t.expiresAt < :before")
    void deleteExpiredBefore(@Param("before") LocalDateTime before);
}
