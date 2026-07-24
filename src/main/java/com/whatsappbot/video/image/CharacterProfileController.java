package com.whatsappbot.video.image;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/character-profiles")
@RequiredArgsConstructor
public class CharacterProfileController {

    private final CharacterProfileService profileService;

    @GetMapping
    public ResponseEntity<List<CharacterProfileResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = tenantId(claims);
        return ResponseEntity.ok(profileService.list(tenantId).stream()
                .map(profile -> toResponse(tenantId, profile))
                .toList());
    }

    @PostMapping
    public ResponseEntity<CharacterProfileResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody CreateCharacterRequest request) {
        UUID tenantId = tenantId(claims);
        return ResponseEntity.ok(toResponse(
                tenantId,
                profileService.create(tenantId, request.name(), request.description(), request.visualStyle())
        ));
    }

    @DeleteMapping("/{profileId}")
    public ResponseEntity<Void> deactivate(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID profileId) {
        profileService.deactivate(tenantId(claims), profileId);
        return ResponseEntity.noContent().build();
    }

    private CharacterProfileResponse toResponse(UUID tenantId, CharacterProfileEntity profile) {
        List<ReferenceAssetResponse> references = profileService.references(tenantId, profile.getId())
                .stream()
                .map(asset -> new ReferenceAssetResponse(
                        asset.getId(), asset.getOriginalName(), asset.getContentType()))
                .toList();
        return new CharacterProfileResponse(
                profile.getId(),
                profile.getName(),
                profile.getDescription(),
                profile.getVisualStyle(),
                references,
                profile.getCreatedAt()
        );
    }

    private UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    record CreateCharacterRequest(String name, String description, String visualStyle) {
    }

    record CharacterProfileResponse(
            UUID id,
            String name,
            String description,
            String visualStyle,
            List<ReferenceAssetResponse> references,
            LocalDateTime createdAt
    ) {
    }

    record ReferenceAssetResponse(UUID id, String originalName, String contentType) {
    }
}
