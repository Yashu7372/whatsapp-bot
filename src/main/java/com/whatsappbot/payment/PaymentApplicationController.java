package com.whatsappbot.payment;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payment-applications")
@RequiredArgsConstructor
public class PaymentApplicationController {

    private final PaymentApplicationService paymentApplicationService;

    @PostMapping
    public ResponseEntity<PaymentApplicationService.ApplicationView> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody PaymentApplicationService.CreateRequest req) {
        return ResponseEntity.ok(
                paymentApplicationService.create(tenantId(claims), userId(claims), req));
    }

    @GetMapping
    public ResponseEntity<List<PaymentApplicationService.ApplicationView>> list(
            @AuthenticationPrincipal Claims claims,
            @RequestParam UUID projectId) {
        return ResponseEntity.ok(paymentApplicationService.list(tenantId(claims), userId(claims), projectId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentApplicationService.ApplicationView> get(
            @AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        return ResponseEntity.ok(paymentApplicationService.getView(tenantId(claims), userId(claims), id));
    }

    // ── Lines ──────────────────────────────────────────────────────────────

    @GetMapping("/{id}/items")
    public ResponseEntity<List<PaymentApplicationService.ItemView>> listItems(
            @AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        return ResponseEntity.ok(paymentApplicationService.listItems(tenantId(claims), userId(claims), id));
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<PaymentApplicationService.ItemView> addItem(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody PaymentApplicationService.AddItemRequest req) {
        return ResponseEntity.ok(paymentApplicationService.addItem(tenantId(claims), userId(claims), id, req));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<Void> removeItem(@AuthenticationPrincipal Claims claims,
                                            @PathVariable UUID id,
                                            @PathVariable UUID itemId) {
        paymentApplicationService.removeItem(tenantId(claims), userId(claims), id, itemId);
        return ResponseEntity.noContent().build();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @PostMapping("/{id}/submit")
    public ResponseEntity<PaymentApplicationService.ApplicationView> submit(
            @AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        return ResponseEntity.ok(paymentApplicationService.submit(tenantId(claims), userId(claims), id));
    }

    @PostMapping("/{id}/certify")
    public ResponseEntity<PaymentApplicationService.ApplicationView> certify(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody(required = false) DecisionRequest req) {
        String comments = req != null ? req.comments() : null;
        return ResponseEntity.ok(
                paymentApplicationService.decide(tenantId(claims), userId(claims), id, true, comments));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<PaymentApplicationService.ApplicationView> reject(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id,
            @RequestBody(required = false) DecisionRequest req) {
        String comments = req != null ? req.comments() : null;
        return ResponseEntity.ok(
                paymentApplicationService.decide(tenantId(claims), userId(claims), id, false, comments));
    }

    @PostMapping("/{id}/paid")
    public ResponseEntity<PaymentApplicationService.ApplicationView> markPaid(
            @AuthenticationPrincipal Claims claims, @PathVariable UUID id,
            @RequestBody(required = false) PaidRequest req) {
        return ResponseEntity.ok(paymentApplicationService.markPaid(tenantId(claims), userId(claims), id,
                req != null ? req.paymentReference() : null));
    }

    private static UUID tenantId(Claims claims) {
        return UUID.fromString((String) claims.get("tenantId"));
    }

    private static UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public record DecisionRequest(String comments) {}

    /** {@code paymentReference} links the release to the bank or ERP record that settled it. */
    public record PaidRequest(String paymentReference) {}
}
