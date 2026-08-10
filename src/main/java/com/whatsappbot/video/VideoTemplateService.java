package com.whatsappbot.video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoTemplateService {

    private final VideoTemplateRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<TemplateResponse> list(UUID tenantId) {
        Map<String, VideoTemplateEntity> merged = new LinkedHashMap<>();
        repository.findAllByActiveTrueAndTenantIsNullOrderByNameAsc()
                .forEach(t -> merged.put(t.getCode(), t));
        repository.findAllByActiveTrueAndTenantIdOrderByNameAsc(tenantId)
                .forEach(t -> merged.put(t.getCode(), t));
        return merged.values().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public VideoTemplateEntity requireTemplate(UUID tenantId, String code) {
        return repository.findFirstByCodeAndTenantIdAndActiveTrue(code, tenantId)
                .or(() -> repository.findFirstByCodeAndTenantIsNullAndActiveTrue(code))
                .orElseThrow(() -> new IllegalArgumentException("Unknown or inactive video template: " + code));
    }

    private TemplateResponse toResponse(VideoTemplateEntity entity) {
        try {
            return new TemplateResponse(entity.getCode(), entity.getName(), entity.getDescription(),
                    objectMapper.readTree(entity.getDefinition()), entity.getTenant() == null);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid template definition for " + entity.getCode(), e);
        }
    }

    public record TemplateResponse(String code, String name, String description,
                                   JsonNode definition, boolean systemTemplate) {}
}
