package com.opsbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsbot.config.CohereProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final WebClient cohereWebClient;
    private final CohereProperties cohereProperties;

    /*
     * We inject CohereProperties here — NOT CohereConfig.
     *
     * CohereProperties is the @Component that holds the actual values.
     * CohereConfig is only responsible for creating the WebClient bean.
     *
     * cohereProperties.getModel() will never be null here because
     * Spring fully binds @ConfigurationProperties before injecting.
     */
    public EmbeddingService(@Qualifier("cohereWebClient") WebClient cohereWebClient,
                            CohereProperties cohereProperties) {
        this.cohereWebClient = cohereWebClient;
        this.cohereProperties = cohereProperties;
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

     * For storing runbook chunks in DB — use search_document.
     * Cohere trains the model differently for storage vs querying.
     */
    public Mono<float[]> generateEmbedding(String text) {
        return generateEmbeddingInternal(text, "search_document");
    }

    /*
     * For querying pgvector at RCA time — use search_query.
     * Using the correct input_type improves retrieval quality.
     */
    public Mono<float[]> generateQueryEmbedding(String text) {
        return generateEmbeddingInternal(text, "search_query");
    }

    private Mono<float[]> generateEmbeddingInternal(String text, String inputType) {

        // cohereProperties.getModel() is "embed-english-v3.0" — never null
        Map<String, Object> requestBody = Map.of(
                "texts", List.of(text),
                "model", cohereProperties.getModel(),
                "input_type", inputType
        );

        return cohereWebClient.post()
                .uri("/v1/embed")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::parseEmbedding)
                .doOnSuccess(v  -> log.debug("Embedding generated dims={}", v.length))
                .doOnError(e    -> log.error("Cohere embedding failed: {}", e.getMessage()))
                .onErrorResume(e -> {
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
            log.error("Unexpected Cohere response: {}", response);
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
        log.info("Vector to string: " + sb.toString());
        return sb.toString();
    }
}