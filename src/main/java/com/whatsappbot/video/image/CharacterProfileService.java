package com.whatsappbot.video.image;

import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.storage.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CharacterProfileService {

    private static final String REFERENCE_ASSET_TYPE = "CHARACTER_REFERENCE";

    private final CharacterProfileRepository profileRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public CharacterProfileEntity create(UUID tenantId, String name, String description, String visualStyle) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Character name is required");
        }
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        CharacterProfileEntity profile = new CharacterProfileEntity();
        profile.setTenant(tenant);
        profile.setName(name.trim());
        profile.setDescription(description == null ? "" : description.trim());
        profile.setVisualStyle(visualStyle == null ? "" : visualStyle.trim());
        return profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public List<CharacterProfileEntity> list(UUID tenantId) {
        return profileRepository.findAllByTenantIdAndActiveTrueOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public CharacterProfileEntity get(UUID tenantId, UUID profileId) {
        return profileRepository.findByIdAndTenantIdAndActiveTrue(profileId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Character profile not found"));
    }

    @Transactional(readOnly = true)
    public List<MediaAssetEntity> references(UUID tenantId, UUID profileId) {
        get(tenantId, profileId);
        return mediaAssetRepository.findAllByTenantIdAndRefIdAndAssetTypeOrderByCreatedAtAsc(
                tenantId, profileId, REFERENCE_ASSET_TYPE);
    }

    @Transactional
    public void deactivate(UUID tenantId, UUID profileId) {
        CharacterProfileEntity profile = get(tenantId, profileId);
        profile.setActive(false);
        profileRepository.save(profile);
    }
}
