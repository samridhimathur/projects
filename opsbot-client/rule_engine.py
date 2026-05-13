"""
Rule-based anomaly detection engine.

Applies deterministic thresholds and pattern matching to collected signals.
No ML/LLM involved - fast and predictable.
"""

from typing import List, Dict, Any, Tuple, Optional
from dataclasses import dataclass
from datetime import datetime, timedelta
import logging
import json
import hashlib
import re

from models import AlertEvent, RuleConfig, MtarToolSignal, Signal
from mcp_tools import MCPToolResult

logger = logging.getLogger(__name__)


@dataclass
class AnomalyDetectionResult:
    """Result of anomaly detection."""
    anomalies_detected: List[AlertEvent]
    rule_hits: List[Tuple[str, str]]  # (rule_name, description)
    signals: Dict[str, Any]
    fingerprints: Dict[str, str]  # alert_type -> fingerprint


class RuleEngine:
    """
    Deterministic rule engine for anomaly detection.

    Rules are simple condition-based (threshold checks, pattern matching).
    No ML involved - ensures predictable, explainable behavior.

    Cooldown logic prevents duplicate alerts within a window.
    """

    # Default rules (can be overridden by config file)
    DEFAULT_RULES = [
        RuleConfig(
            rule_name="high_cpu",
            alert_type="high_cpu",
            severity="HIGH",
            metric_name="cpu_percent",
            operator="greater_than",
            threshold=85.0,
            cooldown_seconds=300
        ),
        RuleConfig(
            rule_name="high_memory",
            alert_type="high_memory",
            severity="HIGH",
            metric_name="memory_percent",
            operator="greater_than",
            threshold=80.0,
            cooldown_seconds=300
        ),
        RuleConfig(
            rule_name="log_error_spike",
            alert_type="log_error_spike",
            severity="CRITICAL",
            metric_name="error_count",
            operator="greater_than",
            threshold=20,
            cooldown_seconds=600
        ),
        RuleConfig(
            rule_name="service_unhealthy",
            alert_type="service_unhealthy",
            severity="CRITICAL",
            metric_name="service_healthy",
            operator="equals",
            threshold=0,  # False
            cooldown_seconds=60
        ),
    ]

    def __init__(self, rules: Optional[List[RuleConfig]] = None):
        """
        Initialize rule engine.

        Args:
            rules: Optional custom rules; uses defaults if None
        """
        self.rules = rules or self.DEFAULT_RULES
        self.last_alert_time: Dict[str, datetime] = {}  # alert_type -> last_time
        logger.info(f"Initialized RuleEngine with {len(self.rules)} rules")

    def detect_anomalies(
        self,
        source: str,
        service: str,
        tool_results: Dict[str, MCPToolResult]
    ) -> AnomalyDetectionResult:
        """
        Detect anomalies from collected signals.

        Args:
            source: Client source identifier
            service: Service name
            tool_results: Results from MCP tools

        Returns:
            AnomalyDetectionResult with any detected anomalies
        """
        anomalies = []
        rule_hits = []
        signals = {}
        fingerprints = {}

        logger.debug(f"Running anomaly detection for {service} on {source}")

        # Aggregate signals from all tools
        for tool_name, result in tool_results.items():
            if result.success:
                signals.update(result.data)

        logger.debug(f"Aggregated signals: {signals}")

        # Apply each rule
        for rule in self.rules:
            metric_value = signals.get(rule.metric_name)

            if metric_value is None:
                logger.debug(f"Metric {rule.metric_name} not available, skipping rule {rule.rule_name}")
                continue

            # Check if rule matches
            if self._check_rule(rule, metric_value):
                logger.info(f"Rule {rule.rule_name} matched! (value={metric_value})")
                rule_hits.append((rule.rule_name, f"{rule.metric_name}={metric_value}"))

                # Check cooldown to avoid duplicate alerts
                if not self._is_in_cooldown(rule.alert_type, rule.cooldown_seconds):
                    alert = self._create_alert(
                        source=source,
                        service=service,
                        rule=rule,
                        signals=signals,
                        tool_results=tool_results
                    )
                    anomalies.append(alert)
                    fingerprints[alert.alert_type] = alert.fingerprint
                    self.last_alert_time[rule.alert_type] = datetime.utcnow()
                    logger.info(f"Created alert: {alert.event_id}")
                else:
                    logger.info(f"Alert {rule.alert_type} in cooldown; suppressing duplicate")

        result = AnomalyDetectionResult(
            anomalies_detected=anomalies,
            rule_hits=rule_hits,
            signals=signals,
            fingerprints=fingerprints
        )

        if anomalies:
            logger.warning(f"Detected {len(anomalies)} anomalies: {[a.alert_type for a in anomalies]}")

        return result

    def _check_rule(self, rule: RuleConfig, metric_value: Any) -> bool:
        """Check if a rule matches the given metric value."""
        try:
            if rule.operator == "greater_than":
                return float(metric_value) > float(rule.threshold)
            elif rule.operator == "less_than":
                return float(metric_value) < float(rule.threshold)
            elif rule.operator == "equals":
                return metric_value == bool(rule.threshold)
            elif rule.operator == "contains":
                return rule.pattern and rule.pattern in str(metric_value)
            elif rule.operator == "regex":
                return rule.pattern and re.search(rule.pattern, str(metric_value))
            else:
                logger.warning(f"Unknown operator: {rule.operator}")
                return False
        except Exception as e:
            logger.error(f"Error checking rule {rule.rule_name}: {e}")
            return False

    def _is_in_cooldown(self, alert_type: str, cooldown_seconds: int) -> bool:
        """Check if an alert type is in its cooldown period."""
        last_time = self.last_alert_time.get(alert_type)
        if last_time is None:
            return False

        elapsed = (datetime.utcnow() - last_time).total_seconds()
        return elapsed < cooldown_seconds

    def _create_alert(
        self,
        source: str,
        service: str,
        rule: RuleConfig,
        signals: Dict[str, Any],
        tool_results: Dict[str, MCPToolResult]
    ) -> AlertEvent:
        """Create an AlertEvent from a matched rule."""

        # Collect context logs
        context_logs = []
        if "log_tail" in tool_results and tool_results["log_tail"].success:
            context_logs = tool_results["log_tail"].data.get("logs", [])[:5]

        # Compute fingerprint (for deduplication on server)
        fingerprint = self._compute_fingerprint(source, service, rule.alert_type)

        timestamp_ms = int(datetime.utcnow().timestamp() * 1000)

        alert = AlertEvent(
            source=source,
            service=service,
            alert_type=rule.alert_type,
            severity=rule.severity,
            detected_at_ms=timestamp_ms,
            signals={k: v for k, v in signals.items() if isinstance(v, (int, float, bool, str))},
            context_logs=context_logs,
            fingerprint=fingerprint,
            metadata={
                "rule_name": rule.rule_name,
                "trigger_value": str(signals.get(rule.metric_name, "unknown")),
                "threshold": str(rule.threshold)
            }
        )

        return alert

    def _compute_fingerprint(self, source: str, service: str, alert_type: str) -> str:
        """
        Compute deduplication fingerprint.

        Hash of (source, service, alertType, timestamp_bucket)
        where timestamp_bucket is current time rounded to nearest 30 seconds.
        """
        timestamp_bucket = (int(datetime.utcnow().timestamp()) // 30) * 30
        combined = f"{source}:{service}:{alert_type}:{timestamp_bucket}"
        return hashlib.md5(combined.encode()).hexdigest()


def load_rules_from_file(file_path: str) -> List[RuleConfig]:
    """Load rules from a JSON config file."""
    try:
        with open(file_path, 'r') as f:
            data = json.load(f)

        rules = []
        for rule_data in data.get("rules", []):
            rule = RuleConfig(**rule_data)
            rules.append(rule)

        logger.info(f"Loaded {len(rules)} rules from {file_path}")
        return rules
    except Exception as e:
        logger.warning(f"Failed to load rules from {file_path}: {e}; using defaults")
        return RuleEngine.DEFAULT_RULES

