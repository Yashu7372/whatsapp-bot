package com.whatsappbot.bootstrap;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.domain.agent.AgentRole;
import com.whatsappbot.domain.agent.TenantAgent;
import com.whatsappbot.domain.agent.TenantAgentRepository;
import com.whatsappbot.domain.tenant.BusinessType;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.features.TenantFeatureEntity;
import com.whatsappbot.features.TenantFeatureRepository;
import com.whatsappbot.subscription.TenantSubscriptionEntity;
import com.whatsappbot.subscription.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalDevBootstrap implements ApplicationRunner {

    private static final String DEFAULT_FAQ_JSON = """
            [
              {"q":"Do you service all car brands?","a":"Yes. We handle Japanese, Korean, American, and European vehicles."},
              {"q":"How do I book a service?","a":"Send your preferred service, vehicle, date, and time. We will confirm an available slot."},
              {"q":"Can I speak to a human agent?","a":"Yes. Ask for a human agent anytime and we will hand the chat over."}
            ]
            """;

    private final TenantRepository tenantRepository;
    private final TenantUserRepository tenantUserRepository;
    private final TenantFeatureRepository tenantFeatureRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;
    private final TenantAgentRepository tenantAgentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.local.bootstrap.enabled:true}")
    private boolean enabled;

    @Value("${app.local.bootstrap.tenant-code:speedwheels}")
    private String tenantCode;

    @Value("${app.local.bootstrap.phone-number-id:LOCAL_AUTOWHEELS_PHONE}")
    private String phoneNumberId;

    @Value("${app.local.bootstrap.admin-email:admin@speedwheels.com}")
    private String adminEmail;

    @Value("${app.local.bootstrap.admin-password:admin123}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        TenantEntity tenant = ensureTenant();
        ensureAdminUser(tenant);
        ensureSubscription(tenant);
        ensureAllFeatures(tenant);
        ensureAgents(tenant);

        log.info("Local dev bootstrap ready. tenantCode={}, adminEmail={}, phoneNumberId={}",
                tenant.getTenantCode(), adminEmail, tenant.getPhoneNumberId());
    }

    private TenantEntity ensureTenant() {
        TenantEntity tenant = tenantRepository.findByTenantCode(tenantCode)
                .orElseGet(TenantEntity::new);

        tenant.setTenantCode(tenantCode);
        tenant.setBusinessName("Auto Wheels Service Center");
        tenant.setBusinessType(BusinessType.AUTOMOBILE);
        tenant.setDefaultLanguage("en");
        tenant.setTimezone("Asia/Dubai");
        tenant.setActive(true);
        tenant.setBusinessHours("Sat-Thu 8:00 AM-6:00 PM");
        tenant.setCrmBusinessType("car_rental");
        tenant.setWhatsappNumber("+971 50 000 0000");
        tenant.setFaqJson(DEFAULT_FAQ_JSON);

        if (tenant.getPhoneNumberId() == null || tenant.getPhoneNumberId().isBlank()
                || tenant.getPhoneNumberId().startsWith("REPLACE_")) {
            tenant.setPhoneNumberId(phoneNumberId);
        }
        if (tenant.getWabaId() == null || tenant.getWabaId().isBlank() || tenant.getWabaId().startsWith("REPLACE_")) {
            tenant.setWabaId("LOCAL_WABA_AUTOWHEELS");
        }

        tenant.setSystemPrompt("""
                You are the WhatsApp assistant for Auto Wheels Service Center in Dubai.
                Help customers with service questions, appointment booking, service history follow-ups,
                and handoff to a human agent when requested. If the customer asks for a human agent,
                reply exactly HUMAN_HANDOFF_REQUIRED.
                """.replace("\n", " ").trim());

        return tenantRepository.save(tenant);
    }

    private void ensureAdminUser(TenantEntity tenant) {
        TenantUserEntity user = tenantUserRepository.findByEmailAndActiveTrue(adminEmail)
                .orElseGet(TenantUserEntity::new);
        user.setTenant(tenant);
        user.setEmail(adminEmail);
        user.setPasswordHash(passwordEncoder.encode(adminPassword));
        user.setFullName("Auto Wheels Admin");
        user.setRole(UserRole.ADMIN);
        user.setActive(true);
        tenantUserRepository.save(user);
    }

    private void ensureSubscription(TenantEntity tenant) {
        TenantSubscriptionEntity subscription = tenantSubscriptionRepository
                .findTopByTenantIdOrderByStartedAtDesc(tenant.getId())
                .orElseGet(TenantSubscriptionEntity::new);
        subscription.setTenant(tenant);
        subscription.setPlanCode("ENTERPRISE");
        subscription.setStatus("ACTIVE");
        subscription.setExpiresAt(null);
        subscription.setCancelledAt(null);
        subscription.setTrialEndsAt(LocalDateTime.now().plusYears(5));
        tenantSubscriptionRepository.save(subscription);
    }

    private void ensureAllFeatures(TenantEntity tenant) {
        List<String> featureCodes = jdbcTemplate.queryForList(
                "select distinct feature_code from plan_features order by feature_code",
                String.class
        );

        Map<String, TenantFeatureEntity> existing = tenantFeatureRepository.findAllByTenantId(tenant.getId())
                .stream()
                .collect(java.util.stream.Collectors.toMap(TenantFeatureEntity::getFeatureCode, f -> f));

        LocalDateTime now = LocalDateTime.now();
        for (String featureCode : featureCodes) {
            TenantFeatureEntity feature = existing.getOrDefault(featureCode, new TenantFeatureEntity());
            feature.setTenant(tenant);
            feature.setFeatureCode(featureCode);
            feature.setEnabled(true);
            feature.setEnabledAt(now);
            feature.setDisabledAt(null);
            tenantFeatureRepository.save(feature);
        }
    }

    private void ensureAgents(TenantEntity tenant) {
        ensureAgent(tenant, "agent1@autowheels.local", "Maya Rahman", agent -> agent.setRole(AgentRole.AGENT));
        ensureAgent(tenant, "agent2@autowheels.local", "Omar Siddiq", agent -> agent.setRole(AgentRole.AGENT));
        ensureAgent(tenant, "lead@autowheels.local", "Aisha Kareem", agent -> agent.setRole(AgentRole.MANAGER));
    }

    private void ensureAgent(TenantEntity tenant, String email, String name, Consumer<TenantAgent> customizer) {
        TenantAgent agent = tenantAgentRepository.findByTenantAndActiveTrueOrderByNameAsc(tenant).stream()
                .filter(existing -> email.equalsIgnoreCase(existing.getEmail()))
                .findFirst()
                .orElseGet(TenantAgent::new);
        agent.setTenant(tenant);
        agent.setEmail(email);
        agent.setName(name);
        agent.setActive(true);
        customizer.accept(agent);
        tenantAgentRepository.save(agent);
    }
}
