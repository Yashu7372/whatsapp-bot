package com.whatsappbot.api;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.contact.ContactRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/contacts")
@RequiredArgsConstructor
public class CrmContactController {

    private final ContactRepository contactRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<ContactResponse>> list(
            @AuthenticationPrincipal Claims claims,
            @RequestParam(required = false) String search) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found"));

        List<ContactEntity> contacts;
        if (search != null && !search.isBlank()) {
            contacts = contactRepository.findByTenantAndDisplayNameContainingIgnoreCase(tenant, search);
        } else {
            contacts = contactRepository.findAllByTenantOrderByLastSeenAtDesc(tenant);
        }

        return ResponseEntity.ok(contacts.stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContactResponse> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        ContactEntity contact = contactRepository.findById(id)
                .filter(c -> c.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + id));
        return ResponseEntity.ok(toResponse(contact));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ContactResponse> update(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody UpdateContactRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        ContactEntity contact = contactRepository.findById(id)
                .filter(c -> c.getTenant().getId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + id));
        if (req.displayName() != null) contact.setDisplayName(req.displayName());
        return ResponseEntity.ok(toResponse(contactRepository.save(contact)));
    }

    private ContactResponse toResponse(ContactEntity c) {
        return new ContactResponse(c.getId(), c.getWaId(), c.getPhoneNumber(),
                c.getDisplayName(), c.getLanguage(), c.getLastSeenAt(), c.getCreatedAt());
    }

    public record ContactResponse(UUID id, String waId, String phoneNumber, String displayName,
                                   String language,
                                   LocalDateTime lastSeenAt, LocalDateTime createdAt) {}

    public record UpdateContactRequest(String displayName) {}
}
