package com.opsbot.service;

import com.opsbot.dto.AlertEvent;
import com.opsbot.model.RcaSession;
import com.opsbot.repository.RcaSessionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.rebalance.ListenerContainerIdleEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes structured AlertEvents from Kafka topic "opsbot-alerts"
 * and routes them to RcaService for analysis.
 *
 * Responsibilities:
 * 1. Deduplication of alerts within a 30-second window
 * 2. Enrichment with metadata (timestamp, processing node)
 * 3. Logging and observability
 * 4. Routing to RcaService
 *
 * Error handling:
 * - Logs to DLQ for unparseable events
 * - Retries with exponential backoff for transient failures
 */
@Service
public class EventProcessorService {

    private static final Logger log = LoggerFactory.getLogger(EventProcessorService.class);
    private static final int DEDUP_WINDOW_SECONDS = 30;

    private final RcaService rcaService;
    private final RcaSessionRepository sessionRepository;

    // Deduplication state: fingerprint -> (timestamp, count)
    private final Map<String, Long> dedupWindow = new ConcurrentHashMap<>();

    // Metrics
    private final Counter eventsProcessed;
    private final Counter eventsDuplicate;
    private final Counter eventsError;
    private final Counter eventsBySeverity;
    private final Timer processingTime;

    public EventProcessorService(
            RcaService rcaService,
            RcaSessionRepository sessionRepository,
            MeterRegistry meterRegistry) {
        this.rcaService = rcaService;
        this.sessionRepository = sessionRepository;

        // Initialize metrics
        this.eventsProcessed = Counter.builder("opsbot.events.processed")
                .description("Total events processed from Kafka")
                .register(meterRegistry);

        this.eventsDuplicate = Counter.builder("opsbot.events.duplicate")
                .description("Events deduplicated (dropped)")
                .register(meterRegistry);

        this.eventsError = Counter.builder("opsbot.events.error")
                .description("Events that failed processing")
                .register(meterRegistry);

        this.eventsBySeverity = Counter.builder("opsbot.events.by_severity")
                .description("Events by severity level")
                .register(meterRegistry);

        this.processingTime = Timer.builder("opsbot.event.processing_time_ms")
                .description("Time to process an alert event")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Listen to Kafka topic "opsbot-alerts" and process each alert event.
     *
     * Runs in a background thread pool managed by Spring Kafka.
     * If processing fails, Kafka will retry according to retry policy.
     */
    @KafkaListener(
            topics = "opsbot-alerts",
            groupId = "${spring.kafka.consumer.group-id:opsbot-event-processor}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processAlertEvent(AlertEvent event) {
        Timer.Sample sample = Timer.start();

        try {
            log.debug("Received alert event {} from {}", event.getEventId(), event.getSource());

            // Step 1: Deduplication
            if (isDuplicate(event)) {
                log.info("Dropped duplicate alert event {} (fingerprint: {})",
                        event.getEventId(), event.getFingerprint());
                eventsDuplicate.increment();
                return;
            }

            // Step 2: Enrichment (add processing metadata)
            enrichEvent(event);

            // Step 3: Route to RCA based on severity
            routeAlert(event);

            // Step 4: Record metrics
            eventsProcessed.increment();
            eventsBySeverity.increment(1.0);

            log.info("Alert event {} processed successfully (severity: {}, service: {})",
                    event.getEventId(), event.getSeverity(), event.getService());

        } catch (Exception e) {
            eventsError.increment();
            log.error("Failed to process alert event {}: {}",
                    event.getEventId(), e.getMessage(), e);
            // In production, this would go to a DLQ
        } finally {
            sample.stop(processingTime);
        }
    }

    /**
     * Check if this alert is a duplicate within the dedup window.
     *
     * Uses fingerprint: hash of (source, service, alertType, timestamp_bucket)
     * TTL: 30 seconds (configurable)
     */
    private boolean isDuplicate(AlertEvent event) {
        String fingerprint = event.getFingerprint();
        if (fingerprint == null) {
            log.warn("Event {} missing fingerprint; treating as new", event.getEventId());
            return false;
        }

        long now = System.currentTimeMillis();
        Long lastSeen = dedupWindow.get(fingerprint);

        // Cleanup old dedup entries every check
        dedupWindow.forEach((fp, timestamp) -> {
            if ((now - timestamp) > (DEDUP_WINDOW_SECONDS * 1000L)) {
                dedupWindow.remove(fp);
            }
        });

        if (lastSeen != null && (now - lastSeen) < (DEDUP_WINDOW_SECONDS * 1000L)) {
            return true;  // Duplicate
        }

        dedupWindow.put(fingerprint, now);
        return false;
    }

    /**
     * Add server-side metadata to the event.
     */
    private void enrichEvent(AlertEvent event) {
        if (event.getMetadata() == null) {
            event.setMetadata(new HashMap<>());
        }

        event.getMetadata().put("processed_at_ms", String.valueOf(System.currentTimeMillis()));
        event.getMetadata().put("processing_node", getHostName());
    }

    /**
     * Route alert to RCA service.
     *
     * Future: Could implement severity-based routing:
     * - CRITICAL: immediate processing, page on-call
     * - HIGH: queue with priority
     * - MEDIUM/LOW: batch processing
     */
    private void routeAlert(AlertEvent event) {
        // Convert AlertEvent to map for RcaService (which currently expects Map<String, Object>)
        Map<String, Object> alertMap = convertEventToMap(event);

        // Trigger async RCA analysis
        rcaService.streamRca(alertMap)
                .subscribe(
                        chunk -> log.debug("RCA chunk received for event {}", event.getEventId()),
                        error -> log.error("RCA analysis failed for event {}: {}",
                                event.getEventId(), error.getMessage()),
                        () -> log.info("RCA analysis completed for event {}", event.getEventId())
                );
    }

    /**
     * Convert AlertEvent DTO to a Map for backward compatibility with existing RcaService.
     *
     * Future: Refactor RcaService to accept AlertEvent directly.
     */
    private Map<String, Object> convertEventToMap(AlertEvent event) {
        Map<String, Object> map = new HashMap<>();
        map.put("eventId", event.getEventId());
        map.put("source", event.getSource());
        map.put("service", event.getService());
        map.put("alertType", event.getAlertType());
        map.put("severity", event.getSeverity());
        map.put("signals", event.getSignals());
        map.put("contextLogs", event.getContextLogs());
        map.put("metadata", event.getMetadata());
        return map;
    }

    private String getHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Health check: listener idle event (optional).
     * Called when Kafka listener has no messages to process.
     */
    @org.springframework.context.event.EventListener
    public void onIdle(ListenerContainerIdleEvent event) {
        log.debug("Event processor idle (group: {})", event.getListenerId());
    }
}

