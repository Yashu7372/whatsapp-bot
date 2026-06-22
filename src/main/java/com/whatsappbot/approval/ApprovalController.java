package com.whatsappbot.approval;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<ApprovalTaskEntity>> listAll(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(approvalService.listByTenant(tenantId));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<ApprovalTaskEntity>> listPending(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(approvalService.listPending(tenantId));
    }

    @PostMapping
    public ResponseEntity<ApprovalTaskEntity> createTask(@RequestBody CreateTaskRequest request) {
        TenantEntity tenant = tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + request.tenantId()));
        return ResponseEntity.ok(approvalService.createTask(tenant, request.contentIdeaId()));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApprovalTaskEntity> approve(@PathVariable UUID id,
                                                       @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(approvalService.approve(id, request.reviewedBy(), request.note()));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApprovalTaskEntity> reject(@PathVariable UUID id,
                                                      @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(approvalService.reject(id, request.reviewedBy(), request.note()));
    }

    @PostMapping("/{id}/request-changes")
    public ResponseEntity<ApprovalTaskEntity> requestChanges(@PathVariable UUID id,
                                                              @RequestBody ReviewRequest request) {
        return ResponseEntity.ok(approvalService.requestChanges(id, request.reviewedBy(), request.note()));
    }

    record CreateTaskRequest(UUID tenantId, UUID contentIdeaId) {}

    record ReviewRequest(String reviewedBy, String note) {}
}
