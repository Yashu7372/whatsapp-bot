package com.whatsappbot.video;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/video-templates")
@RequiredArgsConstructor
public class VideoTemplateController {

    private final VideoTemplateService service;

    @GetMapping
    public ResponseEntity<List<VideoTemplateService.TemplateResponse>> list(
            @AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(service.list(tenantId));
    }
}
