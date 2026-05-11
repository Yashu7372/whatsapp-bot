package com.whatsappbot.domain.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {
    List<ProductEntity> findTop30ByTenantIdAndActiveTrueOrderByCategoryAscNameAsc(UUID tenantId);
    List<ProductEntity> findByTenantIdAndCategoryIdAndActiveTrueOrderByNameAsc(UUID tenantId, UUID categoryId);
    Optional<ProductEntity> findByTenantIdAndRetailerId(UUID tenantId, String retailerId);
}
