package com.whatsappbot.config;

import com.whatsappbot.document.intake.FileTooLargeException;
import com.whatsappbot.document.intake.MalwareDetectedException;
import com.whatsappbot.document.intake.ScannerUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Turns exceptions escaping a controller into a structured JSON body.
 *
 * <p>Without this the response body for a failure was empty and the client had only a status code
 * to work with — and because the container's /error re-dispatch was being rejected by Spring
 * Security, even that code was wrong. A reviewer sending a malformed approval decision and a
 * reviewer who genuinely lacked permission were indistinguishable to the dashboard.
 *
 * <p>The message returned to the client is the exception's reason, never its stack trace: stack
 * traces disclose class names, library versions and query fragments that are useful to an
 * attacker. The full trace is logged server-side instead.
 *
 * <p>This does not weaken the webhook boundary. {@code WebhookController} catches its own
 * processing failures and answers 200 regardless; only a structurally invalid payload — which
 * never reaches the handler method — can surface here.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String ERROR_FIELD = "error";

    /**
     * Carries the status the service layer deliberately chose — 403 for a reviewer who is not
     * the assignee, 409 for an approval that is already closed, 400 for an unrecognised decision.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException e) {
        String reason = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();
        if (e.getStatusCode().is5xxServerError()) {
            log.error("Request failed with server error status {}", e.getStatusCode(), e);
        } else {
            log.warn("Request rejected: {} — {}", e.getStatusCode(), reason);
        }
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(ERROR_FIELD, reason));
    }

    /**
     * A unique or foreign key violation is the client asking for something the data model forbids —
     * reusing a transmittal number, adding the same revision to a package twice — not a server
     * fault. Left to the generic handler these surfaced as 500 "Internal server error", which told
     * the dashboard nothing and looked like an outage.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(DataIntegrityViolationException e) {
        log.warn("Request rejected by a database constraint", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(ERROR_FIELD, "This conflicts with an existing record. "
                        + "It may already exist, or it may reference something that does not."));
    }

    /**
     * Thrown by Spring Security's method-security AOP interceptor when a {@code @PreAuthorize}
     * expression (e.g. {@code @perm.manage(...)}) evaluates false. It's raised from inside the
     * controller invocation, not the filter chain, so {@code ExceptionTranslationFilter} never sees
     * it and the generic handler below would otherwise turn every denial into a misleading 500.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAuthorizationDenied(AuthorizationDeniedException e) {
        log.debug("Access denied by @PreAuthorize: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(ERROR_FIELD, "Not permitted for this action"));
    }

    /** The scanner examined the file and flagged it — the client will not see the file again. */
    @ExceptionHandler(MalwareDetectedException.class)
    public ResponseEntity<Map<String, String>> handleMalwareDetected(MalwareDetectedException e) {
        log.warn("Upload rejected by malware scan: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(ERROR_FIELD, e.getMessage()));
    }

    /** Scanning is required but clamd could not be reached — the client should retry shortly. */
    @ExceptionHandler(ScannerUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleScannerUnavailable(ScannerUnavailableException e) {
        log.error("Malware scanner unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(ERROR_FIELD, e.getMessage()));
    }

    @ExceptionHandler(FileTooLargeException.class)
    public ResponseEntity<Map<String, String>> handleFileTooLarge(FileTooLargeException e) {
        log.warn("Upload rejected: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(ERROR_FIELD, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unhandled exception serving request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(ERROR_FIELD, "Internal server error"));
    }
}
