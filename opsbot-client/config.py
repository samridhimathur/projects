"""
Configuration management for OpsBot client.

Loads settings from environment variables with sensible defaults.
"""

from pydantic_settings import BaseSettings
from typing import Optional
import os


class OpsClientConfig(BaseSettings):
    """
    Configuration for OpsBot client.

    Environment variables:
    - OPSBOT_CLIENT_SOURCE: Client identifier (e.g., "prod-server-01")
    - OPSBOT_CLOUD_URL: Cloud API endpoint base URL
    - OPSBOT_INGESTION_PATH: Ingestion endpoint path (default: /api/ingest)
    - OPSBOT_RETRY_MAX_ATTEMPTS: Max retries for failed sends (default: 3)
    - OPSBOT_RETRY_BACKOFF_SECONDS: Exponential backoff base (default: 2)
    - OPSBOT_BUFFER_DB: SQLite DB path for offline buffering
    - OPSBOT_RULES_CONFIG: Path to rules config JSON
    - OPSBOT_REMEDIATION_PLAYBOOKS: Path to remediation playbooks JSON
    - ANTHROPIC_API_KEY: For MCP tool calls (if using Anthropic backend)
    - COHERE_API_KEY: For embedding (if needed)
    - SLACK_WEBHOOK_URL: Slack incoming webhook for alerts
    - PAGERDUTY_INTEGRATION_KEY: PagerDuty integration key for critical alerts
    """

    # Client identity
    source: str = os.getenv("OPSBOT_CLIENT_SOURCE", "default-client")
    environment: str = os.getenv("OPSBOT_ENVIRONMENT", "dev")

    # Cloud API
    cloud_url: str = os.getenv("OPSBOT_CLOUD_URL", "http://localhost:8080")
    ingestion_path: str = os.getenv("OPSBOT_INGESTION_PATH", "/api/ingest")

    # Retry logic
    retry_max_attempts: int = int(os.getenv("OPSBOT_RETRY_MAX_ATTEMPTS", "3"))
    retry_backoff_seconds: float = float(os.getenv("OPSBOT_RETRY_BACKOFF_SECONDS", "2"))

    # Local buffering
    buffer_db_path: str = os.getenv("OPSBOT_BUFFER_DB", "./opsbot_buffer.db")

    # Rules
    rules_config_path: str = os.getenv("OPSBOT_RULES_CONFIG", "./rules.json")

    # Remediation playbooks
    remediation_playbooks_path: str = os.getenv("OPSBOT_REMEDIATION_PLAYBOOKS", "./remediation_playbooks.json")

    # API keys for MCP tools
    anthropic_api_key: Optional[str] = os.getenv("ANTHROPIC_API_KEY")
    cohere_api_key: Optional[str] = os.getenv("COHERE_API_KEY")

    # Alerting integrations
    slack_webhook_url: Optional[str] = os.getenv("SLACK_WEBHOOK_URL")
    pagerduty_integration_key: Optional[str] = os.getenv("PAGERDUTY_INTEGRATION_KEY")

    # Polling intervals (seconds)
    check_interval_seconds: int = int(os.getenv("OPSBOT_CHECK_INTERVAL_SECONDS", "30"))

    # Logging
    log_level: str = os.getenv("OPSBOT_LOG_LEVEL", "INFO")

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        case_sensitive = False


# Global singleton config
config = OpsClientConfig()

