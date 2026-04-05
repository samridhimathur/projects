package com.opsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsbot.config.CohereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/*
 * EmbeddingService — converts text to float[] using Cohere embed-english-v3.0
 *
 * WHAT IS AN EMBEDDING?
 * A vector (float array) where each number encodes some aspect of meaning.
 * Similar texts produce geometrically close vectors.
 * "memory leak" and "OOM error" will be close. "memory leak" and "pizza" will be far.
 *
 * WHY COHERE embed-english-v3.0?
 * - 1024 dimensions — matches our vector(1024) schema exactly, no changes needed
 * - Excellent for technical text — trained on English technical content
 *
 * MATURE ALTERNATIVES:
 * - Voyage AI voyage-3-lite  → 1024 dims, 200M free tokens (most generous free tier)
 * - OpenAI text-embedding-3-small → 1536 dims, $5 free credit (need schema change)
 * - Google Gemini Embedding  → 3072 dims, free via AI Studio (need schema change)
 * - Ollama nomic-embed-text  → 768 dims, completely free local (need schema change)
 * - LangChain4j              → wraps any of the above with one interface (Week 4)
 *
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient cohereWebClient;
    private final CohereConfig cohereConfig;

    /*
     * @Qualifier("cohereWebClient") tells Spring:
     * "inject the WebClient bean named cohereWebClient"
     *
     * Without @Qualifier, Spring sees two WebClient beans
     * (anthropicWebClient and cohereWebClient) and does not know
     * which one to inject here — it throws NoUniqueBeanDefinitionException.
     *
     * ALTERNATIVE to @Qualifier:
     * Name the parameter exactly the same as the bean:
     *   public EmbeddingService(WebClient cohereWebClient, ...)
     * Spring matches by parameter name as a fallback. But @Qualifier
     * is more explicit and safer — always prefer it.
     */
    public EmbeddingService(@Qualifier("cohereWebClient") WebClient cohereWebClient,
                            CohereConfig cohereConfig) {
        this.cohereWebClient = cohereWebClient;
        this.cohereConfig = cohereConfig;
    }

    /*
     * Converts text to a float[1024] using Cohere's embedding API.
     *
     * Cohere API request shape:
     * {
     *   "texts": ["your text here"],
     *   "model": "embed-english-v3.0",
     *   "input_type": "search_document"   ← for storing in DB
     * }
     *
     * input_type is important:
     *   "search_document" — use when embedding text to STORE in the DB
     *   "search_query"    — use when embedding a QUERY to search with
     * Cohere trained the model differently for each use case.
     * Using the wrong input_type reduces retrieval quality.
     *
     * Cohere API response shape:
     * {
     *   "embeddings": [[0.23, -0.87, 0.41, ...]]  ← array of arrays
     *                   ↑ index 0 = first text's embedding
     * }
     */
    public Mono<float[]> generateEmbedding(String text) {
        return generateEmbedding(text, "search_document");
    }

    /*
     * Overload for query embeddings — used when searching pgvector.
     * The alert payload is a QUERY so it uses "search_query" input type.
     * Runbooks use "search_document" input type.
     */
    public Mono<float[]> generateQueryEmbedding(String text) {
        return generateEmbedding(text, "search_query");
    }

    private Mono<float[]> generateEmbedding(String text, String inputType) {
        Map<String, Object> requestBody = Map.of(
                "texts", List.of(text),
                "model", cohereConfig.getModel(),
                "input_type", inputType
        );

        return cohereWebClient.post()
                .uri("/v1/embed")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(response -> parseEmbedding(response))
                .doOnSuccess(v  -> log.debug("Embedding generated, dims={}", v.length))
                .doOnError(e    -> log.error("Cohere embedding failed: {}", e.getMessage()))
                .onErrorResume(e -> {
                    /*
                     * If Cohere is down or rate limited, fall back to a zero vector.
                     * This means the RCA will run without runbook context rather
                     * than failing entirely. The alert still gets analysed by Claude,
                     * just without relevant runbook chunks injected.
                     *
                     * In production you might want to fail hard here instead,
                     * depending on how critical runbook context is to your RCA quality.
                     */
                    log.warn("Cohere unavailable, using zero vector fallback");
                    return Mono.just(new float[1024]);
                });
    }

    /*
     * Parses Cohere's response JSON into a float[].
     *
     * response.path("embeddings") → the outer array
     * .get(0)                     → first embedding (we only sent one text)
     * loop                        → convert each JsonNode double to float
     */
    private float[] parseEmbedding(JsonNode response) {
        JsonNode embeddingNode = response.path("embeddings").get(0);

        if (embeddingNode == null || !embeddingNode.isArray()) {
            log.error("Unexpected Cohere response shape: {}", response);
            return new float[1024];
        }

        float[] vector = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = (float) embeddingNode.get(i).asDouble();
        }
        return vector;
    }

    /*
     * Converts float[] to pgvector string format: "[0.1,0.2,0.3,...]"
     * Required by the native SQL query:
     *   ORDER BY embedding <=> CAST(:embedding AS vector)
     */
    public String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
