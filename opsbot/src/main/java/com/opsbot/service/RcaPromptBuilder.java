package com.opsbot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsbot.model.RunbookViewProjection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class RcaPromptBuilder {

    private final ObjectMapper objectMapper;

    public RcaPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /*
     * System prompt is STATIC — same for every request.
     * Keeping it static has two benefits:
     * 1. Anthropic can cache it (prompt caching feature) saving cost
     * 2. Claude gets a consistent persona and output format every time
     */
    public String buildSystemPrompt() {
        return """
                You are OpsBot, an expert Site Reliability Engineer performing Root Cause Analysis.
                
                When given an alert payload and relevant runbook context, analyze both and respond
                ONLY with a valid JSON object. No explanation, no markdown, no code fences — raw JSON only.
                
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
                
                If runbook context is provided, use it to inform your fix steps.
                If no relevant context is found, rely on general SRE knowledge.
                """;
    }

    /*
     * User prompt is DYNAMIC — changes per request.
     * Two sections:
     *   1. RUNBOOK CONTEXT — top 5 similar chunks from pgvector
     *   2. ALERT PAYLOAD   — the raw alert JSON
     *
     * Why inject runbook context in USER prompt not SYSTEM prompt?
     * System prompt should be static for caching. Dynamic content
     * (alert data, retrieved chunks) always goes in the user turn.
     */
    public String buildUserPrompt(Map<String, Object> alertPayload,
                                  List<RunbookViewProjection> relevantChunks) {
        try {
            String alertJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(alertPayload);

            StringBuilder prompt = new StringBuilder();

            if (!relevantChunks.isEmpty()) {
                prompt.append("RELEVANT RUNBOOK CONTEXT:\n");
                prompt.append("─".repeat(40)).append("\n");

                for (int i = 0; i < relevantChunks.size(); i++) {
                    RunbookViewProjection chunk = relevantChunks.get(i);
                    prompt.append(String.format("[%d] From: %s\n", i + 1, chunk.getSource()));
                    prompt.append(chunk.getContent().trim());
                    prompt.append("\n\n");
                }
                prompt.append("─".repeat(40)).append("\n\n");
            } else {
                prompt.append("RUNBOOK CONTEXT: No relevant runbooks found.\n\n");
            }

            prompt.append("ALERT PAYLOAD:\n");
            prompt.append(alertJson);
            prompt.append("\n\nAnalyze the alert using the runbook context above and return the RCA JSON:");

            return prompt.toString();

        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize alert payload", e);
        }
    }

    // Overload — no chunks (for tests and fallback)
    public String buildUserPrompt(Map<String, Object> alertPayload) {
        return buildUserPrompt(alertPayload, List.of());
    }
}