"""
Structured logging setup for OpsBot client.

Outputs JSON logs for easy parsing by log aggregation systems.
"""

import logging
import json
from datetime import datetime
from typing import Any, Dict
from pythonjsonlogger import jsonlogger
from config import config


class CustomJsonFormatter(jsonlogger.JsonFormatter):
    """Custom JSON formatter with additional context."""

    def add_fields(self, log_record: Dict[str, Any], record: logging.LogRecord,
                   message_dict: Dict[str, Any]) -> None:
        """Add custom fields to log record."""
        super().add_fields(log_record, record, message_dict)
        log_record['timestamp'] = datetime.utcnow().isoformat()
        log_record['source'] = config.source
        log_record['environment'] = config.environment
        log_record['level'] = record.levelname


def setup_logging(name: str = "opsbot-client") -> logging.Logger:
    """
    Configure structured JSON logging.

    Returns:
        Configured logger instance
    """
    logger = logging.getLogger(name)
    logger.setLevel(config.log_level)

    # Console handler with JSON formatting
    console_handler = logging.StreamHandler()
    console_handler.setLevel(config.log_level)

    formatter = CustomJsonFormatter('%(timestamp)s %(level)s %(name)s %(message)s')
    console_handler.setFormatter(formatter)

    logger.addHandler(console_handler)

    return logger


# Global logger instance
logger = setup_logging()

