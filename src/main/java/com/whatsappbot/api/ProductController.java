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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final TenantRepository tenantRepository;

    @GetMapping
    public ResponseEntity<List<ProductEntity>> list(@AuthenticationPrincipal Claims claims,
                                                     @RequestParam(required = false) UUID categoryId) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        List<ProductEntity> products = categoryId != null
                ? productRepository.findByTenantIdAndCategoryIdAndActiveTrueOrderByNameAsc(tenantId, categoryId)
                : productRepository.findTop30ByTenantIdAndActiveTrueOrderByCategoryAscNameAsc(tenantId);
        return ResponseEntity.ok(products);
    }

    @PostMapping
    public ResponseEntity<ProductEntity> create(@AuthenticationPrincipal Claims claims,
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
        return ResponseEntity.ok(productRepository.save(product));
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

    record CreateProductRequest(String name, String description, String price, String currency,
                                String retailerId, String imageUrl, Integer inventoryCount) {}
}
