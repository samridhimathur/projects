package com.opsbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RcaPromptBuilder {

    private final ObjectMapper objectMapper;

    public RcaPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /*
     * PROMPT ENGINEERING DECISIONS — each one is intentional:
     *
     * 1. SYSTEM prompt sets the persona and hard constraints.
     *    Putting JSON format rules in the SYSTEM prompt (not user prompt) makes
     *    Claude treat them as non-negotiable instructions, not suggestions.
     *
     * 2. We show Claude the EXACT JSON schema we expect.
     *    LLMs are far more reliable when shown the target structure than when
     *    described in prose ("return a JSON with a root_cause field...").
     *
     * 3. "Return ONLY valid JSON. No explanation, no markdown, no code fences."
     *    Without this, Claude wraps the JSON in ```json ... ``` which breaks
     *    Jackson parsing. This instruction eliminates that behaviour.
     *
     * 4. We inject the raw alert payload as a JSON string in the user prompt.
     *    This keeps the system prompt static (cacheable by Anthropic) and puts
     *    the dynamic content in the user turn — better for prompt caching.
     */
    public String buildSystemPrompt() {
        return """
                You are OpsBot, an expert Site Reliability Engineer performing Root Cause Analysis.
                
                When given an alert payload, analyze it and respond ONLY with a valid JSON object.
                No explanation, no markdown, no code fences — raw JSON only.
                
                Your response must exactly match this structure:
                {
                  "root_cause": "A clear one or two sentence explanation of what caused this alert",
                  "severity": "LOW | MEDIUM | HIGH | CRITICAL",
                  "affected_services": ["service-a", "service-b"],
                  "recommended_fix": {
                    "summary": "Brief description of the fix",
                    "steps": [
                      "Step 1: ...",
                      "Step 2: ..."
                    ],
                    "estimated_resolution_time": "X-Y minutes"
                  }
                }
                
                Severity guidelines:
                - CRITICAL: full outage, data loss risk, revenue impact
                - HIGH: major degradation, many users affected
                - MEDIUM: partial degradation, workaround available
                - LOW: minor issue, minimal user impact
                """;
    }

    public String buildUserPrompt(Map<String, Object> alertPayload) {
        try {
            String alertJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(alertPayload);
            return """
                    Analyze this alert and return the RCA JSON:
                    
                    %s
                    """.formatted(alertJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize alert payload", e);
        }
    }
}
