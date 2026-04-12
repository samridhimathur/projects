package com.opsbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/*
 * WHY separate from CohereProperties?
 *
 * Spring's bean lifecycle has two phases:
 *
 * Phase 1 — Bean instantiation
 *   Spring creates all @Component, @Configuration classes
 *   @ConfigurationProperties values are NOT yet bound at this point
 *
 * Phase 2 — Property binding
 *   Spring binds application.yml values to @ConfigurationProperties beans
 *
 * If you put @Bean inside a @ConfigurationProperties class, the @Bean
 * method runs in Phase 1 when fields are still null.
 *
 * By separating them:
 * - CohereProperties is created and fully bound first (Phase 1 + 2)
 * - CohereConfig receives CohereProperties via constructor injection
 * - By the time CohereConfig's @Bean method runs, all values are populated
 *
 * This is the correct Spring pattern — properties class and
 * configuration class are always separate responsibilities.
 */
@Configuration
public class CohereConfig {

    private final CohereProperties cohereProperties;

    /*
     * Constructor injection — Spring injects CohereProperties here.
     * Because CohereProperties is a @Component with @ConfigurationProperties,
     * Spring guarantees it is fully populated before injecting it.
     * No null values possible.
     */
    public CohereConfig(CohereProperties cohereProperties) {
        this.cohereProperties = cohereProperties;
    }

    @Bean
    public WebClient cohereWebClient() {
        /*
         * By the time this method runs, cohereProperties is fully bound.
         * cohereProperties.getBaseUrl() = "https://api.cohere.com"
         * cohereProperties.getKey()     = "your-actual-key"
         * Neither is null.
         */
        return WebClient.builder()
                .baseUrl(cohereProperties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + cohereProperties.getKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
