package com.opsbot.controller;

import com.opsbot.service.RcaService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RcaController {

    private final RcaService rcaService;

    public RcaController(RcaService rcaService) {
        this.rcaService = rcaService;
    }

    /*
     * POST /api/rca
     *
     * Accepts any JSON alert payload — deliberately untyped (Map<String,Object>)
     * because alert schemas differ per monitoring tool.
     *
     * Returns: text/event-stream (SSE)
     * Each chunk from Claude is pushed to the client as it arrives.
     * The browser/client receives the JSON being built in real time.
     *
     * MediaType.TEXT_EVENT_STREAM_VALUE is the SSE content type.
     * Spring WebFlux handles the SSE framing automatically when a
     * controller method returns Flux<String> with this media type —
     * each emitted String becomes a "data: ..." SSE event.
     *
     * Why @RequestBody Map and not a typed DTO?
     * Alert payloads from PagerDuty, Prometheus, and Datadog all have
     * different shapes. A typed DTO would require a separate class per tool.
     * We accept raw JSON and let Claude interpret it — that's actually
     * one of the strengths of using an LLM here.
     */
    @PostMapping(
            value = "/rca",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> analyzeAlert(@RequestBody Map<String, Object> alertPayload) {
        return rcaService.streamRca(alertPayload);
    }

    /*
     * Global error handler for this controller.
     * If Claude returns unparseable JSON (our 500 decision), this catches
     * the IllegalStateException from RcaService and returns a clean error.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleParseError(IllegalStateException ex) {
        return ResponseEntity
                .internalServerError()
                .body(Map.of("error", ex.getMessage()));
    }
}
