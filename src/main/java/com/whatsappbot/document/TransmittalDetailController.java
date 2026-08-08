package com.whatsappbot.document;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transmittals")
public class TransmittalDetailController {
    private final TransmittalDetailService service;

    @GetMapping("/{transmittalId}")
    public ResponseEntity<TransmittalDetailService.Detail> get(@AuthenticationPrincipal Claims claims,@PathVariable UUID transmittalId){
        return ResponseEntity.ok(service.get(tenantId(claims),userId(claims),transmittalId));
    }
    private static UUID tenantId(Claims c){return UUID.fromString((String)c.get("tenantId"));}
    private static UUID userId(Claims c){return UUID.fromString(c.getSubject());}
}
