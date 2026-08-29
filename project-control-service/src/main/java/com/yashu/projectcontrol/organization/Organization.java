package com.yashu.projectcontrol.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    private UUID id;

    @Column(name = "legal_name", nullable = false, length = 240)
    private String legalName;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrganizationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Organization() {
    }

    private Organization(UUID id, String legalName, String displayName, OrganizationStatus status, Instant createdAt) {
        this.id = id;
        this.legalName = legalName;
        this.displayName = displayName;
        this.status = status;
        this.createdAt = createdAt;
    }

    static Organization create(String legalName, String displayName) {
        return new Organization(
                UUID.randomUUID(),
                legalName,
                displayName,
                OrganizationStatus.ACTIVE,
                Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public OrganizationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
