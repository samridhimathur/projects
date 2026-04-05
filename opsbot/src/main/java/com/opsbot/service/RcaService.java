package com.opsbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsbot.client.AnthropicClient;
import com.opsbot.dto.RcaResponse;
import com.opsbot.model.RunbookChunk;
import com.opsbot.model.RcaSession;
import com.opsbot.repository.RcaSessionRepository;
import com.opsbot.repository.RunbookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Service
public class RcaService {

    private static final Logger log = LoggerFactory.getLogger(RcaService.class);

    private final AnthropicClient anthropicClient;
    private final RcaPromptBuilder promptBuilder;
    private final RcaSessionRepository sessionRepository;
    private final RunbookRepository runbookRepository;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public RcaService(AnthropicClient anthropicClient,
                      RcaPromptBuilder promptBuilder,
                      RcaSessionRepository sessionRepository,
                      RunbookRepository runbookRepository,
                      EmbeddingService embeddingService,
                      ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.promptBuilder = promptBuilder;
        this.sessionRepository = sessionRepository;
        this.runbookRepository = runbookRepository;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    /*
     * Full RAG + streaming RCA flow:
     *
     * Step 1 — save PENDING session immediately
     * Step 2 — embed the alert payload (convert to vector)
     * Step 3 — search pgvector for top 5 similar runbook chunks
     * Step 4 — build prompt with alert + runbook context
     * Step 5 — stream Claude response
     * Step 6 — save COMPLETE session when stream finishes
     *
     * Steps 2-3 happen BEFORE streaming starts.
     * Steps 5-6 are concurrent via .cache().
     */
    public Flux<String> streamRca(Map<String, Object> alertPayload) {

        // Step 1 — save session immediately so we have an ID
        RcaSession session = new RcaSession();
        session.setAlertPayload(alertPayload);
        session.setStatus(RcaSession.SessionStatus.PENDING);
        RcaSession saved = sessionRepository.save(session);

        // Steps 2-3-4-5 chained as a reactive pipeline
        // then flatMapMany converts Mono<Flux<String>> → Flux<String>
        return buildAlertText(alertPayload)
                .flatMap(alertText -> embeddingService.generateEmbedding(alertText))
                .flatMap(embedding -> findSimilarChunks(embedding))
                .flatMapMany(chunks -> {
                    // Step 4 — build prompt with runbook context injected
                    String systemPrompt = promptBuilder.buildSystemPrompt();
                    String userPrompt   = promptBuilder.buildUserPrompt(alertPayload, chunks);

                    log.info("Found {} relevant runbook chunks for session {}",
                            chunks.size(), saved.getId());

                    // Step 5 — stream Claude, cache for two consumers
                    Flux<String> sharedStream = anthropicClient
                            .streamRca(systemPrompt, userPrompt)
                            .cache();

                    // Step 6 — collect and persist when stream completes
                    sharedStream
                            .reduce("", String::concat)
                            .flatMap(fullJson -> persistResult(saved, fullJson))
                            .subscribe(
                                    s  -> log.debug("Session {} saved", s.getId()),
                                    e  -> log.error("Failed to persist {}: {}", saved.getId(), e.getMessage())
                            );

                    return sharedStream;
                })
                .onErrorResume(e -> {
                    log.error("RCA pipeline failed for session {}", saved.getId(), e);
                    return Flux.error(new IllegalStateException("RCA failed: " + e.getMessage()));
                });
    }

    /*
     * Converts the alert payload map to a plain text string for embedding.
     * We concatenate all values so the embedding captures the full alert context.
     */
    private Mono<String> buildAlertText(Map<String, Object> alertPayload) {
        String text = alertPayload.values().stream()
                .map(Object::toString)
                .reduce("", (a, b) -> a + " " + b)
                .trim();
        return Mono.just(text);
    }

    /*
     * Queries pgvector for the 5 most similar runbook chunks.
     * This is the RAG retrieval step.
     * subscribeOn(boundedElastic) because findTopSimilarChunks is a blocking JPA call.
     */
    private Mono<List<RunbookChunk>> findSimilarChunks(float[] embedding) {
        String vectorString = embeddingService.vectorToString(embedding);
        return Mono.fromCallable(() ->
                        runbookRepository.findTopSimilarChunks(vectorString)
                )
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(chunks ->
                        log.debug("pgvector returned {} chunks", chunks.size()));
    }

    /*
     * Parse Claude's JSON response and persist as COMPLETE session.
     * Returns 500 if JSON is unparseable (per our design decision).
     */
    private Mono<RcaSession> persistResult(RcaSession session, String rawJson) {
        try {
            RcaResponse rcaResponse = objectMapper.readValue(rawJson, RcaResponse.class);

            if (rcaResponse.getSeverity() != null) {
                rcaResponse.setSeverity(rcaResponse.getSeverity().toUpperCase());
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> rcaMap = objectMapper.convertValue(rcaResponse, Map.class);

            session.setRcaOutput(rcaMap);
            session.setStatus(RcaSession.SessionStatus.COMPLETE);
            return Mono.fromCallable(() -> sessionRepository.save(session))
                    .subscribeOn(Schedulers.boundedElastic());

        } catch (Exception e) {
            log.error("Failed to parse Claude response for session {}: {}",
                    session.getId(), rawJson);
            return Mono.error(new IllegalStateException(
                    "Claude returned unparseable JSON: " + e.getMessage()));
        }
    }
}