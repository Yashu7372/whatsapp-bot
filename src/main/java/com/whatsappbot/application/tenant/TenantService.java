package com.whatsappbot.application.tenant;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public TenantEntity resolveActiveTenant(String phoneNumberId) {
        return tenantRepository.findByPhoneNumberIdAndActiveTrue(phoneNumberId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No active tenant configured for phone_number_id=" + phoneNumberId));
    }
}
