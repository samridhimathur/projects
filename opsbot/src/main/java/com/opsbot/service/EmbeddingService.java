package com.opsbot.service;

import com.opsbot.client.AnthropicClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/*
 * IMPORTANT — INTERVIEW NOTE:
 *
 * Claude does NOT have an embeddings API. It generates text, not vectors.
 * Real embedding models (Cohere, OpenAI, Voyage) are trained specifically
 * to produce vectors where semantic similarity = geometric closeness.
 *
 * What we do here is a WORKAROUND for a portfolio project:
 *   1. Ask Claude to summarize the chunk into key technical terms
 *   2. Hash that summary using SHA-256 into bytes
 *   3. Convert bytes into a float[] of 1024 dimensions
 *
 * This produces DETERMINISTIC vectors (same text = same vector) but they
 * are NOT semantically meaningful — similar incidents won't produce similar
 * vectors. The RAG retrieval will work structurally but won't find truly
 * "similar" incidents the way real embeddings would.
 *
 * To upgrade to real embeddings in production:
 *   - Add Cohere or Voyage AI dependency
 *   - Replace generateEmbedding() with a real API call
 *   - Update vector(1024) to match the model's dimensions
 *   - Everything else (storage, retrieval, pgvector query) stays identical
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int VECTOR_DIMENSIONS = 1024;

    private final AnthropicClient anthropicClient;

    public EmbeddingService(AnthropicClient anthropicClient) {
        this.anthropicClient = anthropicClient;
    }

    /*
     * Generates a pseudo-embedding for a piece of text.
     *
     * Step 1 — ask Claude to extract key technical terms from the text.
     *          This condenses the meaning into a short canonical form.
     * Step 2 — SHA-256 hash the summary into 32 bytes.
     * Step 3 — expand 32 bytes into 1024 floats using a deterministic
     *          spreading function (each byte produces 32 floats via sin/cos).
     * Step 4 — L2-normalize the vector so all vectors have unit length,
     *          making cosine distance meaningful.
     */
    public Mono<float[]> generateEmbedding(String text) {
        String systemPrompt = """
                Extract the key technical terms and concepts from the following text.
                Return ONLY a comma-separated list of terms, no explanation.
                Focus on: service names, error types, metrics, actions, components.
                Example: "high memory, OOM, heap exhaustion, JVM, restart, pod"
                """;

        String userPrompt = "Extract key terms from:\n\n" + text;

        return anthropicClient.completeRca(systemPrompt, userPrompt)
                .map(summary -> {
                    log.debug("Claude summary for embedding: {}", summary);
                    return hashToVector(summary.trim());
                })
                .onErrorReturn(hashToVector(text)); // fallback: hash raw text if Claude fails
    }

    /*
     * Converts a string into a deterministic float[1024] via SHA-256.
     *
     * Why SHA-256?
     * - Deterministic: same input always produces same output
     * - Avalanche effect: small changes in input = very different hash
     * - Fast and available in standard Java (no extra dependencies)
     *
     * The spreading: SHA-256 gives 32 bytes. We need 1024 floats.
     * For each byte b at position i, we generate 32 floats using:
     *   sin(b * (j+1)) and cos(b * (j+1)) alternating
     * This spreads the hash evenly across all 1024 dimensions.
     */
    private float[] hashToVector(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));

            float[] vector = new float[VECTOR_DIMENSIONS];
            for (int i = 0; i < hash.length; i++) {
                for (int j = 0; j < VECTOR_DIMENSIONS / hash.length; j++) {
                    int idx = i * (VECTOR_DIMENSIONS / hash.length) + j;
                    // alternating sin/cos spreads the hash into float space
                    vector[idx] = (float) (j % 2 == 0
                            ? Math.sin(hash[i] * (j + 1))
                            : Math.cos(hash[i] * (j + 1)));
                }
            }

            return l2Normalize(vector);

        } catch (Exception e) {
            log.error("Failed to hash text to vector", e);
            return new float[VECTOR_DIMENSIONS]; // zero vector fallback
        }
    }

    /*
     * L2 normalization: scales the vector so its length = 1.
     * Required for cosine distance to work correctly in pgvector.
     * Without this, longer texts produce larger magnitude vectors and
     * dominate similarity scores regardless of actual content similarity.
     */
    private float[] l2Normalize(float[] vector) {
        double sumOfSquares = 0.0;
        for (float v : vector) {
            sumOfSquares += v * v;
        }
        double magnitude = Math.sqrt(sumOfSquares);

        if (magnitude == 0.0) return vector;

        float[] normalized = Arrays.copyOf(vector, vector.length);
        for (int i = 0; i < normalized.length; i++) {
            normalized[i] = (float) (normalized[i] / magnitude);
        }
        return normalized;
    }

    /*
     * Converts float[] to pgvector string format: "[0.1,0.2,0.3,...]"
     * This is the format the native SQL query expects for CAST(:embedding AS vector).
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