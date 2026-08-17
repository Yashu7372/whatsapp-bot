package com.whatsappbot.features;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeatureCatalogRepository extends JpaRepository<FeatureCatalogEntity, String> {

    List<FeatureCatalogEntity> findAllByOrderByModuleAscSortOrderAsc();
}
