package com.opsbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsbot.config.AnthropicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
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
                           AnthropicProperties properties,
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
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .doOnNext(body -> log.error("Anthropic API error {}: {}",
                                        response.statusCode(), body))
                                .map(body -> new RuntimeException(
                                        "Anthropic API error " + response.statusCode() + ": " + body))
                )
                .bodyToFlux(String.class)
                .doOnNext(line -> log.debug("RAW SSE LINE: {}", line))  // ADD THIS
                .map(line -> line.startsWith("data:") ? line.substring(5).trim() : line.trim())
                .filter(data -> !data.isBlank())
                .filter(data -> !data.equals("[DONE]"))
                .filter(data -> data.startsWith("{"))   // only process JSON objects
                .flatMap(this::extractTextDelta)
                .doOnNext(chunk -> log.info("EXTRACTED CHUNK: {}", chunk))  // ADD THIS
                .doOnComplete(() -> log.info("STREAM COMPLETED"))           // ADD THIS
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
     * An SSE event refers to a message sent over a Server-Sent Events (SSE) connection.

     * What SSE is ??
     * Server-Sent Events is a web technology that allows a server to push real-time updates to a client (usually a browser)
     * over a single long-lived HTTP connection.
     * Unlike WebSockets (which are bidirectional), SSE is one-way: the server can continuously send data to the client,
     * but the client cannot send data back over the same channel.
     * It uses the text/event-stream MIME type.
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
        log.info("PARSING SSE DATA: [{}]", data);

        try {
            //  Parses the raw JSON string into a navigable tree. JsonNode lets you traverse nested JSON without creating a typed class for it.
            JsonNode node = objectMapper.readTree(data);
            // Reads the top-level "type" field. This will be one of: message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop.
            String type = node.path("type").asText();

            log.info("SSE EVENT TYPE: [{}]", type);

            /*
             * Only content_block_delta events carry actual text.
             * All other types (message_start, ping, message_stop etc)
             * are control events — we drop them with Mono.empty().
             */
            if ("content_block_delta".equals(type)) {
                //  Drills into the nested delta object and reads its type. This is "text_delta" for normal text but could
                //  also be "input_json_delta" for tool use events — which we also don't need.
                JsonNode delta = node.path("delta");
                String deltaType = delta.path("type").asText();

                log.info("DELTA TYPE: [{}]", deltaType);

                /*
                 * delta.type can be:
                 *   text_delta       — regular text content — EXTRACT THIS
                 *   input_json_delta — tool use JSON — ignore for our use case
                 */
                if ("text_delta".equals(deltaType)) {
                    String text = delta.path("text").asText();
                    log.info("TEXT CONTENT: [{}]", text);
                    return Mono.just(text);
                }
                //For every other event type — message_start, content_block_stop etc — return Mono.empty(). This emits
                // nothing into the Flux. The event is silently dropped.
                log.info("Ignoring delta type: {}", deltaType);
                return Mono.empty();
            }

            /*
             * Handle error events — Anthropic sometimes sends errors
             * as SSE events mid-stream rather than HTTP status codes.
             * Example: {"type":"error","error":{"type":"overloaded_error","message":"..."}}
             */
            if ("error".equals(type)) {
                String errorType = node.path("error").path("type").asText();
                String errorMsg  = node.path("error").path("message").asText();
                log.error("Anthropic SSE error event type={} message={}", errorType, errorMsg);
                return Mono.error(new RuntimeException(
                        "Anthropic error: " + errorType + " — " + errorMsg));
            }

            // All other event types — silently drop
            log.info("Dropping SSE event type: {}", type);
            return Mono.empty();

        } catch (Exception e) {
            log.warn("Failed to parse SSE event data=[{}] error={}", data, e.getMessage());
            return Mono.empty();
        }
    }
}
