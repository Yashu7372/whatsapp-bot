package com.whatsappbot.domain.vehicle;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<VehicleEntity, UUID> {
    List<VehicleEntity> findByTenantAndContactAndActiveTrue(TenantEntity tenant, ContactEntity contact);

    Optional<VehicleEntity> findByTenantAndPlateNumberIgnoreCaseAndActiveTrue(TenantEntity tenant, String plateNumber);

    List<VehicleEntity> findByTenantAndPlateNumberContainingIgnoreCaseAndActiveTrue(TenantEntity tenant, String plateNumber);
}
