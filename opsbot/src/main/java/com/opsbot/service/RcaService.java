package com.opsbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsbot.client.AnthropicClient;
import com.opsbot.dto.RcaResponse;
import com.opsbot.model.RcaSession;
import com.opsbot.repository.RcaSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class RcaService {

    private static final Logger log = LoggerFactory.getLogger(RcaService.class);

    private final AnthropicClient anthropicClient;
    private final RcaPromptBuilder promptBuilder;
    private final RcaSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    public RcaService(AnthropicClient anthropicClient,
                      RcaPromptBuilder promptBuilder,
                      RcaSessionRepository sessionRepository,
                      ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.promptBuilder = promptBuilder;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    /*
     * STREAMING path — used by the SSE endpoint.
     * Returns a Flux<String> of raw text chunks for real-time display.
     * Also saves the complete assembled response to Postgres when done.
     *
     * .publish() + .autoConnect() — we need two subscribers:
     *   1. The HTTP response (streaming chunks to the browser)
     *   2. The persistence layer (collecting chunks to save when complete)
     * A plain Flux would call Anthropic twice. We use Flux.cache() to
     * share one upstream subscription between both consumers.
     */
    public Flux<String> streamRca(Map<String, Object> alertPayload) {
        String systemPrompt = promptBuilder.buildSystemPrompt();
        String userPrompt = promptBuilder.buildUserPrompt(alertPayload);

        // Save session immediately with PENDING status
        RcaSession session = new RcaSession();
        session.setAlertPayload(alertPayload);
        session.setStatus(RcaSession.SessionStatus.PENDING);
        RcaSession saved = sessionRepository.save(session);

        Flux<String> sharedStream = anthropicClient
                .streamRca(systemPrompt, userPrompt)
                .cache();   // multicast — one Anthropic call, two consumers

        // Side effect: collect full response and persist when stream completes
        sharedStream
                .reduce("", String::concat)
                .flatMap(fullJson -> persistResult(saved, fullJson))
                .subscribe(
                        s -> log.debug("Session {} saved", s.getId()),
                        e -> log.error("Failed to persist session {}: {}", saved.getId(), e.getMessage())
                );

        return sharedStream;
    }

    /*
     * Parse Claude's JSON response and save to Postgres.
     * If parsing fails we return 500 (per our design decision).
     * The raw response is logged so we can debug prompt issues.
     */
    private Mono<RcaSession> persistResult(RcaSession session, String rawJson) {
        try {
            RcaResponse rcaResponse = objectMapper.readValue(rawJson, RcaResponse.class);

            // Normalise severity to uppercase regardless of what Claude returned
            if (rcaResponse.getSeverity() != null) {
                rcaResponse.setSeverity(rcaResponse.getSeverity().toUpperCase());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> rcaMap = objectMapper.convertValue(rcaResponse, Map.class);

            session.setRcaOutput(rcaMap);
            session.setStatus(RcaSession.SessionStatus.COMPLETE);
            return Mono.just(sessionRepository.save(session));

        } catch (Exception e) {
            log.error("Failed to parse Claude response for session {}: {}",
                    session.getId(), rawJson);
            // Re-throw so the 500 handler in the controller catches it
            return Mono.error(new IllegalStateException(
                    "Claude returned unparseable JSON: " + e.getMessage()));
        }
    }
}
