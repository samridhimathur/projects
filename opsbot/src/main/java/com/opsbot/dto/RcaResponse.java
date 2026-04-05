package com.opsbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/*
 * This is the contract we enforce on Claude's output via prompt engineering.
 * Every field here must be explicitly asked for in the prompt — Claude won't
 * invent structure we haven't requested.
 *
 * We use @JsonProperty to map snake_case JSON keys (Claude's output)
 * to camelCase Java fields (our convention).
 */
@Data
@NoArgsConstructor
public class RcaResponse {

    @JsonProperty("root_cause")
    private String rootCause;

    /*
     * Severity as a string enum — we don't use a Java enum here because
     * Claude might occasionally return "CRITICAL" vs "Critical" vs "critical".
     * We normalise it in the service layer after parsing.
     */
    private String severity;                    // LOW | MEDIUM | HIGH | CRITICAL

    @JsonProperty("affected_services")
    private List<String> affectedServices;

    @JsonProperty("recommended_fix")
    private RecommendedFix recommendedFix;

    @Data
    @NoArgsConstructor
    public static class RecommendedFix {
        private String summary;
        private List<String> steps;

        @JsonProperty("estimated_resolution_time")
        private String estimatedResolutionTime;  // e.g. "5-10 minutes"
    }
}