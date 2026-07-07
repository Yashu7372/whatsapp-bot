package com.whatsappbot.domain.contact;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "contacts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_contacts_tenant_wa_id", columnNames = {"tenant_id", "wa_id"})
})
public class ContactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "wa_id", nullable = false, length = 50)
    private String waId;

    @Column(name = "phone_number", nullable = false, length = 50)
    private String phoneNumber;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "language", nullable = false, length = 20)
    private String language = "en";

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static ContactEntity create(TenantEntity tenant, String waId, String phoneNumber, String displayName) {
        ContactEntity contact = new ContactEntity();
        contact.setTenant(tenant);
        contact.setWaId(waId);
        contact.setPhoneNumber(phoneNumber);
        contact.setDisplayName(displayName);
        contact.setLanguage(tenant.getDefaultLanguage());
        contact.markSeenNow();
        return contact;
    }

    public void markSeenNow() {
        this.lastSeenAt = LocalDateTime.now();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
