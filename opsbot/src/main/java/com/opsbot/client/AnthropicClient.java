package com.opsbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsbot.config.AnthropicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final AnthropicProperties properties;
    private final ObjectMapper objectMapper;

    public AnthropicClient(WebClient anthropicWebClient,
                           @Qualifier("anthropicProperties") AnthropicProperties properties,
                           ObjectMapper objectMapper) {
        this.webClient = anthropicWebClient;
        this.properties = properties;
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
                "model", properties.getModel(),
                "max_tokens", properties.getMaxTokens(),
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
     * Takes one raw SSE data line as a String.
     * Parses each SSE data line to extract just the text content.
     * Returns Mono.empty() for non-text events (message_start, ping, etc.)
     * so they are silently filtered out of the Flux.
     *
     *  What Claude actually sends over the wire
        When you call Claude with stream: true, it doesn't send back a clean string. It sends a series of raw SSE events that look like this:
        data: {"type": "message_start", "message": {"id": "msg_123", "model": "claude-sonnet..."}}

        data: {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}

        data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Root"}}

        data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": " cause"}}

        data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": " is"}}

        data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": " memory"}}

        data: {"type": "content_block_stop", "index": 0}

        data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}}

        data: {"type": "message_stop"}
        There are 6+ different event types but you only care about one —
        * content_block_delta with text_delta. That is where the actual text lives.
        * Everything else is metadata about the stream lifecycle.
     */
    private Mono<String> extractTextDelta(String data) {
        try {
            //  Parses the raw JSON string into a navigable tree. JsonNode lets you traverse nested JSON without creating a typed class for it.
            JsonNode node = objectMapper.readTree(data);
            // Reads the top-level "type" field. This will be one of: message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop.
            String type = node.path("type").asText();
            // We only care about delta events — the ones that actually carry text. All others are skipped.
            if ("content_block_delta".equals(type)) {
                //  Drills into the nested delta object and reads its type. This is "text_delta" for normal text but could also be "input_json_delta" for tool use events — which we also don't need.
                String deltaType = node.path("delta").path("type").asText();
                // If it's a text delta, extract the actual text value and wrap it in Mono.just() — this emits one string into the Flux.
                if ("text_delta".equals(deltaType)) {
                    return Mono.just(node.path("delta").path("text").asText());
                }
            }
            //For every other event type — message_start, content_block_stop etc — return Mono.empty(). This emits nothing into the Flux. The event is silently dropped.
            return Mono.empty();    // ignore all other event types
        } catch (Exception e) {
            log.warn("Failed to parse SSE event: {}", data);
            return Mono.empty();
        }
    }
}
