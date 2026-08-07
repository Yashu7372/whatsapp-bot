package com.whatsappbot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unhandled exception serving request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(ERROR_FIELD, "Internal server error"));
    }
}
