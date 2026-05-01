package com.opsbot.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/*
 * @RestControllerAdvice — applies to ALL controllers in the application.
 * This is a centralised exception handler. Without it, unhandled exceptions
 * return Spring's default error format which leaks internal details.
 *
 * Individual controllers can still have their own @ExceptionHandler methods
 * for controller-specific cases. This class only catches what falls through.
 *
 * MATURE ALTERNATIVES:
 * - Spring's ProblemDetail (RFC 7807) — standardised error format
 *   introduced in Spring 6. Better for APIs consumed by other services.
 * - Zalando Problem library — implements RFC 7807 with more features
 * - Custom ErrorAttributes — for customising Spring Boot's /error endpoint
 *
 * We use a simple Map response here — easy to understand and debug.
 * In production, switch to ProblemDetail for a proper standard format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /*
     * Handles @Valid validation failures.
     * Returns 400 with a map of field name → error message.
     *
     * Example response:
     * {
     *   "rating": "rating must be at least 1",
     *   "sessionId": "sessionId is required"
     * }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field   = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(field, message);
        });

        log.warn("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(errors);
    }

    /*
     * Handles any unhandled RuntimeException.
     * Returns 500 with a generic error message.
     * We log the full exception but return only a safe message to the caller.
     *
     * IMPORTANT: never return exception.getMessage() directly in production
     * for unhandled exceptions — it can leak internal implementation details,
     * stack traces, or sensitive data. Log it server-side only.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "An internal error occurred. Check server logs."));
    }

    /*
     * Catch-all for any Exception not handled above.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Unexpected error occurred."));
    }
}
