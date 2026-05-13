"""
Data models for OpsBot client.

Mirrors the AlertEvent class from Java backend for seamless serialization.
"""

from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from datetime import datetime
import uuid


class AlertEvent(BaseModel):
    """
    Structured alert event to be sent to cloud ingestion API.

    Matches com.opsbot.dto.AlertEvent in the Java backend.
    """

    # Unique identifier for this alert event
    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()), alias="eventId")

    # Source client identifier (e.g., "prod-server-01")
    source: str

    # The service/component that triggered the anomaly
    service: str

    # Anomaly type: high_cpu, high_memory, log_error_spike, service_unhealthy, etc.
    alert_type: str = Field(alias="alertType")

    # Severity: LOW, MEDIUM, HIGH, CRITICAL
    severity: str

    # Unix timestamp in milliseconds when anomaly was detected
    detected_at_ms: int = Field(alias="detectedAtMs")

    # Structured signals that triggered the alert
    # e.g., {"cpu_percent": 92.5, "memory_percent": 88.3, "error_count": 150}
    signals: Dict[str, Any]

    # Short-form context logs (max 500 chars total)
    context_logs: Optional[List[str]] = Field(default_factory=list, alias="contextLogs")

    # Fingerprint hash for deduplication (source, service, alertType, timestamp_bucket)
    fingerprint: Optional[str] = None

    # Timestamp when event was created
    created_at: datetime = Field(default_factory=datetime.utcnow, alias="createdAt")

    # Optional metadata
    metadata: Optional[Dict[str, str]] = Field(default_factory=dict)

    class Config:
        populate_by_name = True  # Allow both snake_case and camelCase
        json_schema_extra = {
            "example": {
                "eventId": "550e8400-e29b-41d4-a716-446655440000",
                "source": "prod-server-01",
                "service": "api-gateway",
                "alertType": "high_cpu",
                "severity": "HIGH",
                "detectedAtMs": 1714915522000,
                "signals": {"cpu_percent": 92.5, "memory_percent": 88.3},
                "contextLogs": ["ERROR: CPU spike detected"],
                "fingerprint": "abc123def456",
                "createdAt": "2026-05-02T10:15:22Z",
                "metadata": {"region": "us-west-2"}
            }
        }


class Signal(BaseModel):
    """Individual signal measurement."""
    name: str
    value: float
    unit: Optional[str] = None
    timestamp_ms: int


class RuleConfig(BaseModel):
    """Rule configuration for anomaly detection."""

    rule_name: str
    alert_type: str
    severity: str

    # Condition: metric name and threshold
    metric_name: str
    operator: str  # "greater_than", "less_than", "contains"
    threshold: Optional[float] = None
    pattern: Optional[str] = None  # For regex pattern matching

    # Cooldown: suppress duplicates within N seconds
    cooldown_seconds: int = 60


class MtarToolSignal(BaseModel):
    """Signal collected from MCP tools."""
    timestamp_ms: int
    cpu_percent: Optional[float] = None
    memory_percent: Optional[float] = None
    memory_mb: Optional[float] = None
    disk_percent: Optional[float] = None
    service_healthy: Optional[bool] = None
    error_logs: Optional[List[str]] = Field(default_factory=list)
    response_time_p99_ms: Optional[float] = None
    request_rate_per_sec: Optional[float] = None
    error_rate: Optional[float] = None

