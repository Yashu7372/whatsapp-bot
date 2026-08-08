package com.whatsappbot.document.intake;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Unauthenticated by design — this is how a customer with no account submits a document. Every
 * method is scoped strictly by the link's own token or the session token issued after it; nothing
 * here ever trusts a tenant, project or document id supplied by the caller.
 */
@RestController
@RequestMapping("/api/v1/public/upload-links")
@RequiredArgsConstructor
public class PublicUploadLinkController {

    private final UploadLinkService service;

    @GetMapping("/{token}")
    public ResponseEntity<UploadLinkService.LinkMetadata> metadata(@PathVariable String token) {
        return ResponseEntity.ok(service.metadata(token));
    }

    @PostMapping("/{token}/verify")
    public ResponseEntity<SessionResponse> verify(@PathVariable String token,
                                                   @RequestBody(required = false) VerifyRequest req,
                                                   HttpServletRequest httpRequest) {
        String password = req != null ? req.password() : null;
        String sessionToken = service.startSession(token, password, clientIp(httpRequest));
        return ResponseEntity.ok(new SessionResponse(sessionToken));
    }

    @PostMapping(value = "/{token}/documents", consumes = "multipart/form-data")
    public ResponseEntity<UploadResponse> upload(@PathVariable String token,
                                                 @RequestHeader(value = "Authorization", required = false) String authHeader,
                                                 @RequestPart("file") MultipartFile file,
                                                 @RequestPart(value = "uploaderName", required = false) String uploaderName,
                                                 @RequestPart(value = "uploaderEmail", required = false) String uploaderEmail,
                                                 HttpServletRequest httpRequest) {
        String sessionToken = bearerToken(authHeader);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A file is required");
        }
        try {
            var doc = service.upload(sessionToken, file.getOriginalFilename(), file.getContentType(),
                    file.getInputStream(), blankToNull(uploaderName), blankToNull(uploaderEmail),
                    clientIp(httpRequest));
            return ResponseEntity.ok(new UploadResponse(doc.getId().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    private String bearerToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session token required");
        }
        return authHeader.substring(7);
    }

    /**
     * Use the servlet peer address for security throttling. X-Forwarded-For is caller-controlled
     * unless the deployment has an explicitly trusted proxy strategy, so trusting it here would
     * let an attacker rotate spoofed addresses and bypass password throttling.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record VerifyRequest(String password) {}
    public record SessionResponse(String sessionToken) {}
    /** Deliberately just an opaque reference — the anonymous submitter never gets read access to
     *  the document they created, only proof it was received. */
    public record UploadResponse(String reference) {}
}
