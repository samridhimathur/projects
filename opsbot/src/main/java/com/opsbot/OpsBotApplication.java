package com.opsbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.opsbot.config.CohereProperties;
import com.opsbot.config.AnthropicProperties;
import org.springframework.context.ApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties({CohereProperties.class, AnthropicProperties.class})
public class OpsBotApplication {
    private static final Logger log = LoggerFactory.getLogger(OpsBotApplication.class);

    public static void main(String[] args) {
        ApplicationContext ctx = SpringApplication.run(OpsBotApplication.class, args);

        CohereProperties cohere = ctx.getBean(CohereProperties.class);
        log.info("=== CONFIG CHECK ===");
        log.info("cohere.model   = {}", cohere.getModel());
        log.info("cohere.baseUrl = {}", cohere.getBaseUrl());
        log.info("cohere.key     = {}", cohere.getKey() != null ? "present" : "NULL");
        log.info("====================");
    }
}