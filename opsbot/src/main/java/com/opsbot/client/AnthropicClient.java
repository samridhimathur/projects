package com.opsbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsbot.config.AnthropicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class AnthropicClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    private final WebClient webClient;
    private final AnthropicConfig config;
    private final ObjectMapper objectMapper;

    public AnthropicClient(WebClient anthropicWebClient,
                           AnthropicConfig config,
                           ObjectMapper objectMapper) {
        this.webClient = anthropicWebClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /*
     * STREAMING CALL — returns a Flux<String> of text chunks as they arrive.
     *
     * Anthropic's streaming API uses Server-Sent Events (SSE).
     * Each event looks like:
     *   data: {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Root"}}
     *   data: {"type": "content_block_delta", "delta": {"type": "text_delta", "text": " cause"}}
     *   data: {"type": "message_stop"}
     *
     * We filter for only "text_delta" events and extract the text field,
     * building up the full response on the client side.
     *
     * Why Flux and not a simple String return?
     * Flux is a reactive stream — it lets us push each chunk to the HTTP response
     * as soon as it arrives from Anthropic, rather than buffering the entire
     * response in memory first. This is what makes streaming feel instant to the user.
     */
    public Flux<String> streamRca(String systemPrompt, String userPrompt) {
        Map<String, Object> requestBody = Map.of(
                "model", config.getModel(),
                "max_tokens", config.getMaxTokens(),
                "stream", true,                         // enable SSE streaming
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        return webClient.post()
                .uri("/v1/messages")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)               // raw SSE lines as strings
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())  // strip "data: " prefix
                .filter(data -> !data.equals("[DONE]"))
                .flatMap(this::extractTextDelta)
                .doOnError(e -> log.error("Anthropic streaming error: {}", e.getMessage()));
    }

    /*
     * NON-STREAMING CALL — collects the full streaming response into one String.
     *
     * We build on top of streamRca() rather than making a separate non-streaming
     * API call. This keeps one code path for Anthropic communication.
     * Flux.reduce() concatenates all chunks into a single accumulated string.
     */
    public Mono<String> completeRca(String systemPrompt, String userPrompt) {
        return streamRca(systemPrompt, userPrompt)
                .reduce("", String::concat)
                .doOnSuccess(result -> log.debug("Claude response: {}", result));
    }

    /*
     * Parses each SSE data line to extract just the text content.
     * Returns Mono.empty() for non-text events (message_start, ping, etc.)
     * so they are silently filtered out of the Flux.
     */
    private Mono<String> extractTextDelta(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            String type = node.path("type").asText();

            if ("content_block_delta".equals(type)) {
                String deltaType = node.path("delta").path("type").asText();
                if ("text_delta".equals(deltaType)) {
                    return Mono.just(node.path("delta").path("text").asText());
                }
            }
            return Mono.empty();    // ignore all other event types
        } catch (Exception e) {
            log.warn("Failed to parse SSE event: {}", data);
            return Mono.empty();
        }
    }
}
