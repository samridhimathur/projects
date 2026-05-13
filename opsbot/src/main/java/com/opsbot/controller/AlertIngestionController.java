package com.opsbot.controller;

import com.opsbot.dto.AlertEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Ingestion endpoint for receiving structured alert events from clients.
 *
 * This is the cloud-side entry point for client-generated anomaly events.
 * Currently stateless (no auth); events are validated and published to Kafka queue.
 *
 * API Gateway / authentication will be added in Phase 2.
 */
@RestController
@RequestMapping("/api/ingest")
public class AlertIngestionController {

    private static final Logger log = LoggerFactory.getLogger(AlertIngestionController.class);

    private final KafkaTemplate<String, AlertEvent> kafkaTemplate;
    private final String alertsTopic;
    private final Counter eventCounter;
    private final Counter validationErrorCounter;

    public AlertIngestionController(
            KafkaTemplate<String, AlertEvent> kafkaTemplate,
            @Value("${application.kafka.topics.alerts:opsbot-alerts}") String alertsTopic,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.alertsTopic = alertsTopic;

        // Metrics
        this.eventCounter = Counter.builder("opsbot.events.received")
                .description("Total structured alert events received")
                .tag("endpoint", "ingest")
                .register(meterRegistry);

        this.validationErrorCounter = Counter.builder("opsbot.events.validation_error")
                .description("Alert events failed validation")
                .register(meterRegistry);
    }

    /**
     * POST /api/ingest
     *
     * Accept a structured AlertEvent from the client and publish to Kafka queue.
     *
     * Request:
     * {
     *   "eventId": "550e8400-e29b-41d4-a716-446655440000",
     *   "source": "prod-server-01",
     *   "service": "api-gateway",
     *   "alert_type": "high_cpu",
     *   "severity": "HIGH",
     *   "detectedAtMs": 1714915522000,
     *   "signals": {"cpu_percent": 92.5},
     *   "contextLogs": ["ERROR: CPU spike detected"],
     *   "fingerprint": "abc123def456",
     *   "created_at": "2026-05-02T10:15:22Z",
     *   "metadata": {"region": "us-west-2"}
     * }
     *
     * Response: 202 Accepted (event queued for async processing)
     * or 400 Bad Request (validation failure)
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> ingestAlert(@RequestBody AlertEvent event) {

        // Validate required fields
        if (event == null || event.getEventId() == null || event.getSource() == null ||
            event.getService() == null || event.getAlertType() == null) {
            log.warn("Validation failed: missing required fields in alert event");
            validationErrorCounter.increment();
            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Missing required fields: eventId, source, service, alert_type"));
        }

        try {
            // Publish to Kafka queue
            log.debug("Publishing alert event {} from {} to topic {}",
                    event.getEventId(), event.getSource(), alertsTopic);

            kafkaTemplate.send(alertsTopic, event.getEventId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish event {}: {}", event.getEventId(), ex.getMessage());
                        } else {
                            log.info("Event {} published to partition {}",
                                    event.getEventId(),
                                    result.getRecordMetadata().partition());
                        }
                    });

            eventCounter.increment();

            log.info("Alert event {} accepted for processing (source: {}, service: {}, type: {})",
                    event.getEventId(), event.getSource(), event.getService(), event.getAlertType());

            return ResponseEntity
                    .accepted()
                    .body(Map.of("eventId", event.getEventId(), "status", "accepted"));

        } catch (Exception e) {
            log.error("Error ingesting alert event {}: {}", event.getEventId(), e.getMessage(), e);
            return ResponseEntity
                    .internalServerError()
                    .body(Map.of("error", "Failed to queue event: " + e.getMessage()));
        }
    }
}

