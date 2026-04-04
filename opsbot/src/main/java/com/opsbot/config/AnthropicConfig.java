package com.opsbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConfigurationProperties(prefix = "anthropic.api")
public class AnthropicConfig {

    private String key;
    private String baseUrl;
    private String model;
    private int maxTokens;

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
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", key)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .codecs(config -> config
                        .defaultCodecs()
                        .maxInMemorySize(2 * 1024 * 1024))  // 2MB buffer for large responses
                .build();
    }

    // Getters and setters — required by @ConfigurationProperties
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
}
