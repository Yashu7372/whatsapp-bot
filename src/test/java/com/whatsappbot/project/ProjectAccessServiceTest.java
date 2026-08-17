package com.whatsappbot.project;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ProjectAccessServiceTest {
    @Mock TenantUserRepository users;
    @Mock ProjectParticipantRepository participants;

    @Test
    void organizationManagerIsNotTenantAdministrator(){
        ProjectAccessService service=new ProjectAccessService(users,participants);
        TenantUserEntity manager=user(UserRole.MANAGER,UUID.randomUUID());
        assertThat(service.isTenantAdministrator(manager)).isFalse();
    }

    @Test
    void unattachedTenantManagerIsTenantAdministrator(){
        ProjectAccessService service=new ProjectAccessService(users,participants);
        TenantUserEntity manager=user(UserRole.MANAGER,null);
        assertThat(service.isTenantAdministrator(manager)).isTrue();
    }

    @Test
    void unattachedViewerIsNotTenantAdministrator(){
        ProjectAccessService service=new ProjectAccessService(users,participants);
        assertThat(service.isTenantAdministrator(user(UserRole.VIEWER,null))).isFalse();
    }

    private TenantUserEntity user(UserRole role,UUID organizationId){
        TenantEntity tenant=new TenantEntity();tenant.setId(UUID.randomUUID());
        TenantUserEntity user=new TenantUserEntity();user.setTenant(tenant);user.setRole(role);user.setOrganizationId(organizationId);user.setActive(true);
        return user;
    }
}
