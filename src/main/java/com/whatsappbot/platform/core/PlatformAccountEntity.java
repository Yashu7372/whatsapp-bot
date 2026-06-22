package com.whatsappbot.platform.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "platform_accounts")
@Getter
@Setter
@NoArgsConstructor
public class PlatformAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 50)
    private String platformCode;

    @Column(length = 200)
    private String externalAccountId;

    @Column(length = 200)
    private String accountName;

    @Column(length = 200)
    private String accountHandle;

    @Column(length = 300)
    private String credentialRef;

    @Column(columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
