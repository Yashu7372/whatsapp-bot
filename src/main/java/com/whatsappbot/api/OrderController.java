package com.whatsappbot.api;

import com.whatsappbot.domain.order.WhatsappOrderEntity;
import com.whatsappbot.domain.order.WhatsappOrderRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final WhatsappOrderRepository whatsappOrderRepository;

    @GetMapping
    public ResponseEntity<List<WhatsappOrderEntity>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(whatsappOrderRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<WhatsappOrderEntity> updateStatus(@AuthenticationPrincipal Claims claims,
                                                             @PathVariable UUID id,
                                                             @RequestBody StatusRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return whatsappOrderRepository.findById(id)
                .filter(o -> o.getTenant().getId().equals(tenantId))
                .map(o -> {
                    o.setStatus(request.status());
                    o.setUpdatedAt(LocalDateTime.now());
                    return ResponseEntity.ok(whatsappOrderRepository.save(o));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    record StatusRequest(String status) {}
}
