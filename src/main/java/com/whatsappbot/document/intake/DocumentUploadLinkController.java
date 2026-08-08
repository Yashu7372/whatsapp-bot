package com.whatsappbot.document.intake;

import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import com.whatsappbot.project.ProjectAccessService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Agent-side: create, list and revoke shareable upload links. Creation is tenant-admin only —
 *  a link commits the tenant to accepting documents from whoever holds the URL. */
@RestController
@RequestMapping("/api/v1/document-upload-links")
@RequiredArgsConstructor
public class DocumentUploadLinkController {

    private final UploadLinkService service;
    private final ProjectAccessService accessService;
    private final FeatureAccessService featureAccessService;
    private final UploadLinkProperties properties;

    @PostMapping
    public ResponseEntity<LinkResponse> create(@AuthenticationPrincipal Claims claims,
                                               @RequestBody CreateLinkRequest req) {
        UUID tenantId = tenantId(claims), userId = userId(claims);
        assertAccess(tenantId);
        requireAdministrator(tenantId, userId);
        var link = service.create(tenantId, userId, new UploadLinkService.CreateRequest(
                req.projectId(), req.docType(), req.label(), req.password(), req.expiresAt(), req.maxUploads()));
        return ResponseEntity.ok(toResponse(link));
    }

    @GetMapping
    public ResponseEntity<List<LinkResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = tenantId(claims);
        assertAccess(tenantId);
        return ResponseEntity.ok(service.list(tenantId).stream().map(this::toResponse).toList());
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        UUID tenantId = tenantId(claims), userId = userId(claims);
        assertAccess(tenantId);
        requireAdministrator(tenantId, userId);
        service.revoke(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    private void requireAdministrator(UUID tenantId, UUID userId) {
        var actor = accessService.requireActiveUser(tenantId, userId);
        if (!accessService.isTenantAdministrator(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a tenant administrator can manage upload links");
        }
    }

    private void assertAccess(UUID tenantId) {
        featureAccessService.assertAccess(tenantId, FeatureCode.DOCUMENT_CONTROL);
    }

    private static UUID tenantId(Claims c) { return UUID.fromString((String) c.get("tenantId")); }
    private static UUID userId(Claims c) { return UUID.fromString(c.getSubject()); }

    private LinkResponse toResponse(DocumentUploadLinkEntity l) {
        String url = properties.getPublicBaseUrl() + "/upload/" + l.getToken();
        return new LinkResponse(l.getId(), l.getLabel(), l.getDocType(), l.getProjectId(), url,
                l.requiresPassword(), l.getMaxUploads(), l.getUploadCount(), l.getExpiresAt(),
                l.getRevokedAt(), l.getCreatedAt());
    }

    public record CreateLinkRequest(UUID projectId, String docType, String label, String password,
                                    LocalDateTime expiresAt, Integer maxUploads) {}

    public record LinkResponse(UUID id, String label, String docType, UUID projectId, String url,
                               boolean requiresPassword, Integer maxUploads, int uploadCount,
                               LocalDateTime expiresAt, LocalDateTime revokedAt, LocalDateTime createdAt) {}
}
