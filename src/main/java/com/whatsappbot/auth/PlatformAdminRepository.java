package com.whatsappbot.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlatformAdminRepository extends JpaRepository<PlatformAdminEntity, UUID> {

    Optional<PlatformAdminEntity> findByEmailAndActiveTrue(String email);
}
