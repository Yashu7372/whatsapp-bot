package com.whatsappbot.project;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Manages the companies a tenant works with. */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final TenantRepository tenantRepository;

    @Transactional
    public OrganizationEntity create(UUID tenantId, CreateOrganizationRequest req) {
        String orgCode = normaliseCode(req.orgCode());
        if (organizationRepository.existsByTenantIdAndOrgCodeIgnoreCase(tenantId, orgCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An organization with code '" + orgCode + "' already exists");
        }

        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        OrganizationEntity org = new OrganizationEntity();
        org.setTenant(tenant);
        org.setName(req.name());
        org.setOrgCode(orgCode);
        org.setTradeLicense(req.tradeLicense());
        org.setContactEmail(req.contactEmail());
        org.setContactPhone(req.contactPhone());

        OrganizationEntity saved = organizationRepository.save(org);
        log.info("Organization created. id={} code={} tenant={}", saved.getId(), orgCode, tenantId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<OrganizationEntity> list(UUID tenantId, boolean activeOnly) {
        return activeOnly
                ? organizationRepository.findAllByTenantIdAndActiveTrueOrderByNameAsc(tenantId)
                : organizationRepository.findAllByTenantIdOrderByNameAsc(tenantId);
    }

    @Transactional(readOnly = true)
    public OrganizationEntity get(UUID tenantId, UUID orgId) {
        return organizationRepository.findByIdAndTenantId(orgId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Organization not found: " + orgId));
    }

    @Transactional
    public OrganizationEntity update(UUID tenantId, UUID orgId, UpdateOrganizationRequest req) {
        OrganizationEntity org = get(tenantId, orgId);
        if (req.name() != null) org.setName(req.name());
        if (req.tradeLicense() != null) org.setTradeLicense(req.tradeLicense());
        if (req.contactEmail() != null) org.setContactEmail(req.contactEmail());
        if (req.contactPhone() != null) org.setContactPhone(req.contactPhone());
        if (req.active() != null) org.setActive(req.active());
        return organizationRepository.save(org);
    }

    /**
     * Codes appear in document references, so they are stored uppercase and without surrounding
     * space. Doing this on the way in keeps ACME and acme from becoming two companies.
     */
    private String normaliseCode(String orgCode) {
        if (orgCode == null || orgCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orgCode is required");
        }
        return orgCode.trim().toUpperCase();
    }

    public record CreateOrganizationRequest(String name, String orgCode, String tradeLicense,
                                             String contactEmail, String contactPhone) {}

    public record UpdateOrganizationRequest(String name, String tradeLicense, String contactEmail,
                                             String contactPhone, Boolean active) {}
}
