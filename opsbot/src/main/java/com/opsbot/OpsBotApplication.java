package com.opsbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.opsbot.config.CohereProperties;
import com.opsbot.config.AnthropicProperties;

@SpringBootApplication
@EnableConfigurationProperties({CohereProperties.class, AnthropicProperties.class})
public class OpsBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsBotApplication.class, args);
    }
}