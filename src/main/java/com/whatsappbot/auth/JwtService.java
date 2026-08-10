package com.whatsappbot.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;

    public String generateAccessToken(TenantUserEntity user) {
        return buildToken(user, jwtProperties.getAccessExpirySeconds(), "access");
    }

    public String generateRefreshToken(TenantUserEntity user) {
        return buildToken(user, jwtProperties.getRefreshExpirySeconds(), "refresh");
    }

    public String generatePlatformAccessToken(PlatformAdminEntity admin) {
        return buildPlatformToken(admin, jwtProperties.getAccessExpirySeconds(), "access");
    }

    public String generatePlatformRefreshToken(PlatformAdminEntity admin) {
        return buildPlatformToken(admin, jwtProperties.getRefreshExpirySeconds(), "refresh");
    }

    private String buildToken(TenantUserEntity user, long expirySeconds, String tokenType) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("tenantId", user.getTenant().getId().toString())
                .claim("role", user.getRole().name())
                .claim("type", tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(secretKey())
                .compact();
    }

    private String buildPlatformToken(PlatformAdminEntity admin, long expirySeconds, String tokenType) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(admin.getId().toString())
                .claim("email", admin.getEmail())
                .claim("scope", "PLATFORM")
                .claim("role", "PLATFORM_SUPER_ADMIN")
                .claim("type", tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(secretKey())
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public UUID extractTenantId(String token) {
        return UUID.fromString((String) parseClaims(token).get("tenantId"));
    }

    public boolean isAccessToken(Claims claims) {
        return "access".equals(claims.get("type"));
    }

    private SecretKey secretKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
