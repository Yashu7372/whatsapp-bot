package com.whatsappbot.features;

import com.whatsappbot.subscription.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeatureAccessService {

    private final TenantFeatureRepository tenantFeatureRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;

    public void assertSubscriptionActive(UUID tenantId) {
        if (!tenantSubscriptionRepository.existsActiveSubscription(tenantId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Subscription is not active");
        }
    }

    public void assertFeatureEnabled(UUID tenantId, String featureCode) {
        if (!tenantFeatureRepository.existsByTenantIdAndFeatureCodeAndEnabledTrue(tenantId, featureCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Feature not enabled for this tenant: " + featureCode);
        }
    }

    public void assertAccess(UUID tenantId, String featureCode) {
        assertSubscriptionActive(tenantId);
        assertFeatureEnabled(tenantId, featureCode);
    }

    public boolean isFeatureEnabled(UUID tenantId, String featureCode) {
        return tenantFeatureRepository.existsByTenantIdAndFeatureCodeAndEnabledTrue(tenantId, featureCode);
    }
}
