package com.whatsappbot.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TenantUserRepository userRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest req) {
        PlatformAdminEntity admin = platformAdminRepository.findByEmailAndActiveTrue(req.email())
                .orElse(null);
        if (admin != null) {
            if (!passwordEncoder.matches(req.password(), admin.getPasswordHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new TokenResponse(null, null, null, "Invalid credentials"));
            }
            admin.setLastLoginAt(LocalDateTime.now());
            platformAdminRepository.save(admin);

            String access = jwtService.generatePlatformAccessToken(admin);
            String refresh = jwtService.generatePlatformRefreshToken(admin);
            log.info("Platform admin logged in. email={}", admin.getEmail());

            return ResponseEntity.ok(new TokenResponse(access, refresh, null, null));
        }

        TenantUserEntity user = userRepository.findByEmailAndActiveTrue(req.email())
                .orElse(null);

        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new TokenResponse(null, null, null, "Invalid credentials"));
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        String access  = jwtService.generateAccessToken(user);
        String refresh = jwtService.generateRefreshToken(user);
        log.info("User logged in. email={} tenantId={}", user.getEmail(), user.getTenant().getId());

        return ResponseEntity.ok(new TokenResponse(access, refresh,
                user.getTenant().getId().toString(), null));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest req) {
        try {
            var claims = jwtService.parseClaims(req.refreshToken());
            if (!"refresh".equals(claims.get("type"))) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new TokenResponse(null, null, null, "Not a refresh token"));
            }

            if ("PLATFORM".equals(claims.get("scope"))) {
                PlatformAdminEntity admin = platformAdminRepository.findById(jwtService.extractUserId(req.refreshToken()))
                        .orElseThrow(() -> new IllegalArgumentException("Platform admin not found"));
                String access = jwtService.generatePlatformAccessToken(admin);
                return ResponseEntity.ok(new TokenResponse(access, req.refreshToken(), null, null));
            }

            TenantUserEntity user = userRepository.findById(jwtService.extractUserId(req.refreshToken()))
                    .orElseThrow(() -> new IllegalArgumentException("User not found"));

            String access = jwtService.generateAccessToken(user);
            return ResponseEntity.ok(new TokenResponse(access, req.refreshToken(),
                    user.getTenant().getId().toString(), null));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new TokenResponse(null, null, null, "Invalid or expired refresh token"));
        }
    }

    // ── Records ──────────────────────────────────────────────────────────

    public record LoginRequest(String email, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record TokenResponse(String accessToken, String refreshToken, String tenantId, String error) {}
}
