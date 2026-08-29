package com.yashu.projectcontrol.scope;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ScopeCapabilityRepository extends JpaRepository<ScopeCapability, UUID> {
    Optional<ScopeCapability> findByScopeIdAndCapabilityCode(UUID scopeId, String capabilityCode);

    List<ScopeCapability> findByScopeIdOrderByCapabilityCodeAsc(UUID scopeId);
}
