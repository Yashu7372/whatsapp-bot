package com.whatsappbot.api;

import com.whatsappbot.domain.product.ProductEntity;
import com.whatsappbot.domain.product.ProductRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<ProductResponse>> list(@AuthenticationPrincipal Claims claims,
                                                     @RequestParam(required = false) UUID categoryId) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        List<ProductEntity> products = categoryId != null
                ? productRepository.findByTenantIdAndCategoryIdAndActiveTrueOrderByNameAsc(tenantId, categoryId)
                : productRepository.findTop30ByTenantIdAndActiveTrueOrderByCategoryAscNameAsc(tenantId);
        return ResponseEntity.ok(products.stream().map(ProductController::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@AuthenticationPrincipal Claims claims,
                                                 @RequestBody CreateProductRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        ProductEntity product = new ProductEntity();
        product.setTenant(tenant);
        product.setName(request.name());
        product.setDescription(request.description());
        product.setPrice(request.price() != null ? new BigDecimal(request.price()) : null);
        product.setCurrency(request.currency() != null ? request.currency() : "AED");
        product.setRetailerId(request.retailerId());
        product.setImageUrl(request.imageUrl());
        product.setInventoryCount(request.inventoryCount());
        product.setActive(true);
        return ResponseEntity.ok(toResponse(productRepository.save(product)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Claims claims, @PathVariable UUID id) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        productRepository.findById(id).ifPresent(p -> {
            if (p.getTenant().getId().equals(tenantId)) {
                p.setActive(false);
                productRepository.save(p);
            }
        });
        return ResponseEntity.noContent().build();
    }

    /**
     * Maps to a DTO instead of returning the entity directly. {@code tenant} and
     * {@code category} are LAZY associations and {@code open-in-view} is false, so
     * serialising the entity itself fails with a LazyInitializationException once a
     * tenant actually has products. Reading only the association id is safe — Hibernate
     * answers it from the proxy without hitting the database.
     */
    private static ProductResponse toResponse(ProductEntity p) {
        return new ProductResponse(
                p.getId(),
                p.getCategory() != null ? p.getCategory().getId() : null,
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getCurrency(),
                p.getRetailerId(),
                p.getWhatsappCatalogId(),
                p.getImageUrl(),
                p.getInventoryCount(),
                p.isActive(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }

    public record ProductResponse(UUID id, UUID categoryId, String name, String description,
                                  BigDecimal price, String currency, String retailerId,
                                  String whatsappCatalogId, String imageUrl, Integer inventoryCount,
                                  boolean active, LocalDateTime createdAt, LocalDateTime updatedAt) {}

    record CreateProductRequest(String name, String description, String price, String currency,
                                String retailerId, String imageUrl, Integer inventoryCount) {}
}
