package com.opsbot.controller;

import com.opsbot.model.RcaSession;
import com.opsbot.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /*
     * GET /api/sessions
     *
     * Returns the 20 most recent RCA sessions newest first.
     * Used by the frontend to populate the session history table.
     *
     * Returns Flux<RcaSession> — Spring WebFlux serialises this
     * to a JSON array automatically. Each RcaSession becomes one
     * JSON object in the array.
     *
     * Why Flux and not Mono<List>?
     * Flux lets Spring stream the array items as they are loaded
     * from the DB rather than waiting for all 20 before responding.
     * For 20 items the difference is negligible but it is the
     * correct reactive pattern.
     */
    @GetMapping("/sessions")
    public Flux<RcaSession> getSessions() {
        return sessionService.getRecentSessions();
    }

    /*
     * GET /api/sessions/{id}
     *
     * Returns a single session by UUID.
     * Used when the frontend needs the full rca_output JSON for display.
     *
     * @PathVariable extracts the {id} segment from the URL.
     * UUID.fromString() throws IllegalArgumentException if malformed.
     */
    @GetMapping("/sessions/{id}")
    public Mono<ResponseEntity<RcaSession>> getSession(@PathVariable String id) {
        return sessionService.getSession(UUID.fromString(id))
                .map(ResponseEntity::ok);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).body(ex.getMessage());
    }
}
