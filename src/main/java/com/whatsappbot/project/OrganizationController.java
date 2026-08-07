package com.whatsappbot.project;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    @PostMapping
    public ResponseEntity<OrganizationResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody OrganizationService.CreateOrganizationRequest req) {
        return ResponseEntity.ok(toResponse(organizationService.create(tenantId(claims), req)));
    }

    @GetMapping
    public ResponseEntity<List<OrganizationResponse>> list(
            @AuthenticationPrincipal Claims claims,
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        return ResponseEntity.ok(organizationService.list(tenantId(claims), activeOnly)
                .stream().map(OrganizationController::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrganizationResponse> get(@AuthenticationPrincipal Claims claims,
                                                     @PathVariable UUID id) {
        return ResponseEntity.ok(toResponse(organizationService.get(tenantId(claims), id)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<OrganizationResponse> update(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody OrganizationService.UpdateOrganizationRequest req) {
        return ResponseEntity.ok(toResponse(organizationService.update(tenantId(claims), id, req)));
    }

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    // Mapped to a DTO rather than returned directly: the entity's tenant is a LAZY association
    // and open-in-view is false, so serialising the entity would fail.
    private static OrganizationResponse toResponse(OrganizationEntity o) {
        return new OrganizationResponse(o.getId(), o.getName(), o.getOrgCode(), o.getTradeLicense(),
                o.getContactEmail(), o.getContactPhone(), o.isActive(), o.getCreatedAt(), o.getUpdatedAt());
    }

    public record OrganizationResponse(UUID id, String name, String orgCode, String tradeLicense,
                                        String contactEmail, String contactPhone, boolean active,
                                        LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
