"""
OpsBot Client: Lightweight anomaly detection and event producer

Responsibilities:
1. Collect system signals via MCP tools (logs, metrics, health)
2. Apply deterministic rule-based anomaly detection
3. Build structured AlertEvent JSON
4. Send to cloud ingestion API with retry logic
5. Persist failed events to local SQLite buffer
"""

__version__ = "0.1.0"
__author__ = "OpsBot Team"

