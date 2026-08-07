package com.whatsappbot.api;

import com.whatsappbot.application.tunnel.DevTunnelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhook/tunnel")
@RequiredArgsConstructor
public class CrmTunnelController {

    private final DevTunnelService devTunnelService;

    @GetMapping("/status")
    public ResponseEntity<DevTunnelService.TunnelSnapshot> status() {
        return ResponseEntity.ok(devTunnelService.snapshot());
    }

    @PostMapping("/start")
    public ResponseEntity<DevTunnelService.TunnelSnapshot> start() {
        return ResponseEntity.ok(devTunnelService.start());
    }

    @PostMapping("/stop")
    public ResponseEntity<DevTunnelService.TunnelSnapshot> stop() {
        return ResponseEntity.ok(devTunnelService.stop());
    }
}
