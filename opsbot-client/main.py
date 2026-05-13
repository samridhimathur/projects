"""
Main OpsBot Client orchestrator.

Coordinates signal collection, anomaly detection, and event sending.
"""

import asyncio
import logging
import signal
import sys
from typing import Optional

from config import config
from logger import logger
from signal_collector import SystemSignalCollector
from rule_engine import RuleEngine, load_rules_from_file
from buffer import EventBuffer
from sender import CloudAPISender
from remediation_engine import RemediationEngine

logger = logger  # Use configured logger


class OpsBotClient:
    """
    Main client application.

    Workflow:
    1. Collect signals from system (real metrics, not mocks)
    2. Run rule-based anomaly detection
    3. Send detected anomalies to cloud API
    4. Buffer and retry failed sends
    5. Execute local remediation in parallel
    """

    def __init__(self):
        """Initialize client components."""
        self.signal_collector = SystemSignalCollector()
        self.buffer = EventBuffer(config.buffer_db_path)
        self.sender = CloudAPISender(self.buffer)

        # Load rules from config or use defaults
        rules = load_rules_from_file(config.rules_config_path)
        self.rule_engine = RuleEngine(rules)

        # Initialize remediation engine for local automated remediation
        self.remediation_engine = RemediationEngine(config.remediation_playbooks_path)

        self.running = False
        logger.info(f"Initialized OpsBotClient (source={config.source}, env={config.environment})")
        logger.info(f"Signal collector ready (real system metrics)")
        logger.info(f"Remediation engine ready with playbooks")

    async def run(self):
        """Start the client in continuous monitoring mode."""
        self.running = True

        # Setup signal handlers for graceful shutdown
        for sig in (signal.SIGTERM, signal.SIGINT):
            asyncio.get_event_loop().add_signal_handler(
                sig, lambda s=sig: asyncio.create_task(self.shutdown(s))
            )

        logger.info("Starting OpsBotClient monitoring loop")

        # Run concurrent tasks
        try:
            await asyncio.gather(
                self._monitoring_loop(),
                self.sender.poll_and_send()
            )
        except Exception as e:
            logger.error(f"Client error: {e}", exc_info=True)
        finally:
            await self.shutdown()

    async def _monitoring_loop(self):
        """
        Main monitoring loop.

        Periodically collects real system signals, detects anomalies, and sends events.
        """
        while self.running:
            try:
                logger.debug("Starting monitoring cycle")

                # Step 1: Collect signals (REAL system metrics, not mocks)
                logger.info("Collecting system signals...")
                signal_results = await self.signal_collector.collect_all_signals()

                # Log collected metrics
                metrics_result = signal_results.get("system_metrics")
                if metrics_result and metrics_result.success:
                    metrics = metrics_result.data
                    logger.info(
                        f"System metrics: CPU={metrics.get('cpu_percent', 0):.1f}%, "
                        f"Memory={metrics.get('memory_percent', 0):.1f}%"
                    )

                # Step 2: Detect anomalies using deterministic rules
                logger.info("Running anomaly detection...")
                detection_result = self.rule_engine.detect_anomalies(
                    source=config.source,
                    service="application",
                    tool_results=signal_results
                )

                # Step 3: Process detected anomalies
                if detection_result.anomalies_detected:
                    logger.info(f"Detected {len(detection_result.anomalies_detected)} anomalies")

                    for alert in detection_result.anomalies_detected:
                        # Step 3a: Execute local remediation (in parallel with cloud send)
                        # This is fast and happens immediately on the client
                        remediation_task = asyncio.create_task(
                            self.remediation_engine.execute_remediation(alert)
                        )

                        # Step 3b: Send to cloud (non-blocking)
                        send_task = asyncio.create_task(
                            self.sender.send_event_with_buffer(alert)
                        )

                        # Wait for both to complete
                        try:
                            remediation_result, send_success = await asyncio.gather(
                                remediation_task,
                                send_task,
                                return_exceptions=False
                            )

                            if remediation_result.success:
                                logger.info(
                                    f"Alert {alert.event_id}: remediation SUCCESS "
                                    f"({remediation_result.total_duration_seconds:.2f}s)"
                                )
                            else:
                                logger.warning(
                                    f"Alert {alert.event_id}: remediation FAILED "
                                    f"- {remediation_result.escalation_reason}"
                                )

                            if send_success:
                                logger.info(f"Alert {alert.event_id} sent to cloud successfully")
                            else:
                                logger.warning(f"Alert {alert.event_id} buffered (will retry)")

                        except Exception as e:
                            logger.error(f"Error processing alert {alert.event_id}: {e}", exc_info=True)
                else:
                    logger.debug("No anomalies detected")

                # Wait before next cycle
                logger.debug(f"Sleeping {config.check_interval_seconds}s until next check")
                await asyncio.sleep(config.check_interval_seconds)

            except Exception as e:
                logger.error(f"Error in monitoring loop: {e}", exc_info=True)
                await asyncio.sleep(config.check_interval_seconds)

    async def shutdown(self, sig: Optional[int] = None):
        """Graceful shutdown."""
        if sig:
            logger.info(f"Received signal {sig}, shutting down...")

        self.running = False

        # Final flush of buffered events
        pending_stats = self.buffer.get_stats()
        if pending_stats.get("total_buffered", 0) > 0:
            logger.info(f"Flushing {pending_stats['total_buffered']} buffered events...")
            successful, failed = await self.sender.replay_buffered_events()
            logger.info(f"Flush result: {successful} sent, {failed} remain buffered")

        logger.info("OpsBotClient shutdown complete")
        sys.exit(0)

    def run_sync(self):
        """Run client synchronously (wrapper for asyncio)."""
        try:
            asyncio.run(self.run())
        except KeyboardInterrupt:
            logger.info("Interrupted by user")


def main():
    """Entry point for the client."""
    logger.info(f"=== OpsBot Client v0.1.0 ===")
    logger.info(f"Configuration: source={config.source}, env={config.environment}")
    logger.info(f"Cloud API: {config.cloud_url}{config.ingestion_path}")

    client = OpsBotClient()
    client.run_sync()


if __name__ == "__main__":
    main()

