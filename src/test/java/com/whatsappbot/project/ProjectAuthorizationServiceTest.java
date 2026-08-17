package com.whatsappbot.project;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAuthorizationServiceTest {
    @Mock ProjectAccessService access;
    @Mock ProjectCapabilityRepository capabilityRepository;

    @Test
    void contractorManagerGetsOrganizationCommercialScope(){
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),userId=UUID.randomUUID(),org=UUID.randomUUID();
        TenantUserEntity actor=new TenantUserEntity();actor.setId(userId);actor.setRole(UserRole.MANAGER);actor.setOrganizationId(org);actor.setActive(true);
        when(access.requireActiveUser(tenant,userId)).thenReturn(actor);
        when(access.isTenantAdministrator(actor)).thenReturn(false);
        when(access.rolesOnProject(tenant,project,actor)).thenReturn(List.of(PartyRole.CONTRACTOR));

        ProjectAuthorizationService.Decision d=new ProjectAuthorizationService(access,capabilityRepository)
                .require(tenant,userId,project,ProjectPermission.COMMERCIAL_VIEW_ORGANIZATION);

        assertThat(d.scope()).isEqualTo(ProjectAuthorizationService.DataScope.ORGANIZATION);
        assertThat(d.organizationId()).isEqualTo(org);
    }

    @Test
    void clientManagerGetsProjectPaymentScope(){
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),userId=UUID.randomUUID(),org=UUID.randomUUID();
        TenantUserEntity actor=new TenantUserEntity();actor.setId(userId);actor.setRole(UserRole.MANAGER);actor.setOrganizationId(org);actor.setActive(true);
        when(access.requireActiveUser(tenant,userId)).thenReturn(actor);
        when(access.isTenantAdministrator(actor)).thenReturn(false);
        when(access.rolesOnProject(tenant,project,actor)).thenReturn(List.of(PartyRole.CLIENT));

        ProjectAuthorizationService.Decision d=new ProjectAuthorizationService(access,capabilityRepository)
                .require(tenant,userId,project,ProjectPermission.PAYMENT_VIEW_PROJECT);

        assertThat(d.scope()).isEqualTo(ProjectAuthorizationService.DataScope.PROJECT);
    }
}
