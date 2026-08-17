package com.whatsappbot.delivery;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read model for navigating a project as delivery work rather than as disconnected registers.
 *
 * <p>The underlying document, approval, commercial and resource services remain the systems of
 * record. This API composes those facts around Project -> Stage -> Work Package -> Work Item so
 * every dashboard number can be drilled back to the work that produced it.
 */
@RestController
@RequestMapping("/api/v1/project-delivery")
@RequiredArgsConstructor
public class ProjectDeliveryController {

    private final ProjectDeliveryService service;

    @GetMapping("/portfolio")
    public ResponseEntity<ProjectDeliveryService.PortfolioView> portfolio(
            @AuthenticationPrincipal Claims claims) {
        return ResponseEntity.ok(service.portfolio(tenantId(claims), userId(claims)));
    }

    @GetMapping("/projects/{projectId}")
    public ResponseEntity<ProjectDeliveryService.ProjectDetailView> project(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(service.project(tenantId(claims), userId(claims), projectId));
    }

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private static UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }
}
