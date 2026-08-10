package com.whatsappbot.features;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeatureApiPathRepository extends JpaRepository<FeatureApiPathEntity, java.util.UUID> {

    List<FeatureApiPathEntity> findAll();
}
