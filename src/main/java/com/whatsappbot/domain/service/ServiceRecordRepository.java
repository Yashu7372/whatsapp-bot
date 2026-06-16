package com.whatsappbot.domain.service;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.vehicle.VehicleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRecordRepository extends JpaRepository<ServiceRecordEntity, UUID> {

    Optional<ServiceRecordEntity> findTopByTenantAndVehicleOrderByServiceDateDesc(TenantEntity tenant, VehicleEntity vehicle);

    List<ServiceRecordEntity> findByTenantAndVehicleOrderByServiceDateDesc(TenantEntity tenant, VehicleEntity vehicle);

    List<ServiceRecordEntity> findTop20ByTenantAndContactOrderByServiceDateDesc(TenantEntity tenant, ContactEntity contact);
}
