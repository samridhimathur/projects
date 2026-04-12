package com.opsbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cohere.api")
public class CohereProperties {

    private String key;
    private String baseUrl;
    private String model;

    // Getters and setters required by @ConfigurationProperties
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
