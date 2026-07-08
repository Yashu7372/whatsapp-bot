package com.whatsappbot.api;

import com.whatsappbot.domain.agent.TenantAgent;
import com.whatsappbot.domain.agent.TenantAgentRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/crm/agents")
@RequiredArgsConstructor
public class CrmAgentController {

    private final TenantRepository tenantRepository;
    private final TenantAgentRepository tenantAgentRepository;

    @GetMapping
    public ResponseEntity<List<AgentResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));

        List<AgentResponse> agents = tenantAgentRepository.findByTenantAndActiveTrueOrderByNameAsc(tenant)
                .stream()
                .map(agent -> new AgentResponse(
                        agent.getId(),
                        agent.getName(),
                        agent.getEmail(),
                        agent.getRole().name(),
                        agent.isActive()
                ))
                .toList();

        return ResponseEntity.ok(agents);
    }

    public record AgentResponse(UUID id, String name, String email, String role, boolean active) {}
}
