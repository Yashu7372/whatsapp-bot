package com.whatsappbot.projectscope;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProjectScopeController {

    private final ProjectScopeService service;

    @GetMapping("/api/v1/project-scope-types")
    public ResponseEntity<List<ProjectScopeRepository.ScopeTypeRow>> scopeTypes(
            @AuthenticationPrincipal Claims claims) {
        return ResponseEntity.ok(service.listTypes(tenantId(claims), userId(claims)));
    }

    @PostMapping("/api/v1/projects/{projectId}/scopes")
    public ResponseEntity<ProjectScopeRepository.ScopeRow> create(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId,
            @RequestBody ProjectScopeService.CreateScopeRequest request) {
        return ResponseEntity.ok(service.create(tenantId(claims), userId(claims), projectId, request));
    }

    @GetMapping("/api/v1/projects/{projectId}/scopes")
    public ResponseEntity<List<ProjectScopeRepository.ScopeRow>> list(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId) {
        return ResponseEntity.ok(service.list(tenantId(claims), userId(claims), projectId));
    }

    @GetMapping("/api/v1/projects/{projectId}/scopes/{scopeId}")
    public ResponseEntity<ProjectScopeRepository.ScopeRow> get(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId) {
        return ResponseEntity.ok(service.get(tenantId(claims), userId(claims), projectId, scopeId));
    }

    @PatchMapping("/api/v1/projects/{projectId}/scopes/{scopeId}")
    public ResponseEntity<ProjectScopeRepository.ScopeRow> update(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @RequestBody ProjectScopeService.UpdateScopeRequest request) {
        return ResponseEntity.ok(service.update(tenantId(claims), userId(claims), projectId, scopeId, request));
    }

    @GetMapping("/api/v1/projects/{projectId}/scopes/{scopeId}/capabilities")
    public ResponseEntity<List<ProjectScopeRepository.CapabilityRow>> capabilities(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId) {
        return ResponseEntity.ok(service.capabilities(tenantId(claims), userId(claims), projectId, scopeId));
    }

    @PutMapping("/api/v1/projects/{projectId}/scopes/{scopeId}/capabilities/{capabilityCode}")
    public ResponseEntity<ProjectScopeRepository.CapabilityRow> putCapability(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @PathVariable String capabilityCode,
            @RequestBody ProjectScopeService.PutCapabilityRequest request) {
        return ResponseEntity.ok(service.putCapability(tenantId(claims), userId(claims), projectId,
                scopeId, capabilityCode, request));
    }

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private static UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }
}
