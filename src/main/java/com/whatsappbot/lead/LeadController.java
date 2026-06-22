package com.whatsappbot.lead;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadSignalService leadSignalService;

    @GetMapping
    public ResponseEntity<List<LeadSignalEntity>> listSignals(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(leadSignalService.listByTenant(tenantId));
    }
}
