package com.opsbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class AnthropicConfig {

    private final AnthropicProperties anthropicProperties;

    public AnthropicConfig(AnthropicProperties anthropicProperties) {
        this.anthropicProperties = anthropicProperties;
    }

    /*
     * WebClient is Spring WebFlux's non-blocking HTTP client.
     * We use it instead of RestTemplate because:
     * 1. RestTemplate has no streaming support — it waits for the full response
     * 2. WebClient handles Server-Sent Events (SSE) natively, which is exactly
     *    what Anthropic's streaming API returns
     *
     * The base headers are set once here so every request automatically includes
     * the API key and Anthropic version — no repetition in the client class.
     */
    @Bean
    public WebClient anthropicWebClient() {
        return WebClient.builder()
                .baseUrl(anthropicProperties.getBaseUrl())
                .defaultHeader("x-api-key", anthropicProperties.getKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .codecs(config -> config
                        .defaultCodecs()
                        .maxInMemorySize(2 * 1024 * 1024))
                .build();
    }
}