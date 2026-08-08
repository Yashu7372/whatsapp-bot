package com.whatsappbot.document;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approval-worklist")
@RequiredArgsConstructor
public class ApprovalWorklistController {
    private final ApprovalWorklistService service;

    @GetMapping("/mine")
    public ResponseEntity<List<ApprovalWorklistService.Item>> mine(@AuthenticationPrincipal Claims claims){
        return ResponseEntity.ok(service.mine(tenantId(claims),userId(claims)));
    }

    @PostMapping("/escalations/refresh")
    public ResponseEntity<Map<String,Integer>> refresh(@AuthenticationPrincipal Claims claims){
        return ResponseEntity.ok(Map.of("escalated",service.refreshEscalations(tenantId(claims),userId(claims))));
    }

    private static UUID tenantId(Claims c){return UUID.fromString((String)c.get("tenantId"));}
    private static UUID userId(Claims c){return UUID.fromString(c.getSubject());}
}
