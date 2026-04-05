package com.opsbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ConfigurationProperties(prefix = "cohere.api")
public class CohereConfig {

    private String key;
    private String baseUrl;
    private String model;

    /*
     * Separate WebClient for Cohere — different base URL and auth header.
     * We name it "cohereWebClient" so Spring knows this is a different
     * bean from "anthropicWebClient". Without the @Bean name, Spring
     * would get confused when injecting WebClient by type since we have two.
     */
    @Bean
    public WebClient cohereWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + key)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // Getters and setters required by @ConfigurationProperties
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
