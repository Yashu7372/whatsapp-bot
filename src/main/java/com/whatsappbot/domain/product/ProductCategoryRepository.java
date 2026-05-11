package com.whatsappbot.domain.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductCategoryRepository extends JpaRepository<ProductCategoryEntity, UUID> {
    List<ProductCategoryEntity> findByTenantIdAndActiveTrueOrderBySortOrderAscNameAsc(UUID tenantId);
}
