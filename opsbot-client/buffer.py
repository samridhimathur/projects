"""
Local event buffer for offline resilience.

Stores events in SQLite when cloud connection is unavailable.
Replays queued events when connectivity is restored.
"""

import sqlite3
import json
import logging
from typing import List, Optional
from datetime import datetime
from models import AlertEvent

logger = logging.getLogger(__name__)


class EventBuffer:
    """
    Local SQLite buffer for persistent event storage.

    Stores events that fail to send to cloud API.
    On reconnection, replays events in order.
    """

    def __init__(self, db_path: str = "./opsbot_buffer.db"):
        """
        Initialize event buffer.

        Args:
            db_path: Path to SQLite database file
        """
        self.db_path = db_path
        self._init_db()
        logger.info(f"Initialized EventBuffer at {db_path}")

    def _init_db(self):
        """Initialize database schema."""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        cursor.execute("""
            CREATE TABLE IF NOT EXISTS buffered_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT UNIQUE NOT NULL,
                event_json TEXT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                retry_count INTEGER DEFAULT 0,
                last_error TEXT,
                last_retry_at TIMESTAMP
            )
        """)

        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_created_at
            ON buffered_events(created_at)
        """)

        cursor.execute("""
            CREATE INDEX IF NOT EXISTS idx_retry_count
            ON buffered_events(retry_count)
        """)

        conn.commit()
        conn.close()

    def add_event(self, event: AlertEvent) -> bool:
        """
        Add an event to the buffer.

        Args:
            event: AlertEvent to buffer

        Returns:
            True if successfully buffered, False if event already exists
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            event_json = event.model_dump_json()

            cursor.execute(
                """
                INSERT INTO buffered_events (event_id, event_json)
                VALUES (?, ?)
                """,
                (event.event_id, event_json)
            )

            conn.commit()
            conn.close()

            logger.info(f"Buffered event {event.event_id}")
            return True

        except sqlite3.IntegrityError:
            logger.warning(f"Event {event.event_id} already in buffer")
            return False
        except Exception as e:
            logger.error(f"Failed to buffer event {event.event_id}: {e}")
            return False

    def get_pending_events(self, limit: int = 100) -> List[AlertEvent]:
        """
        Get pending events for retry.

        Returns events sorted by creation time (earliest first).

        Args:
            limit: Max number of events to return

        Returns:
            List of AlertEvent objects
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            cursor.execute(
                """
                SELECT event_json FROM buffered_events
                ORDER BY created_at ASC
                LIMIT ?
                """,
                (limit,)
            )

            rows = cursor.fetchall()
            conn.close()

            events = []
            for (event_json,) in rows:
                try:
                    event = AlertEvent.model_validate_json(event_json)
                    events.append(event)
                except Exception as e:
                    logger.warning(f"Failed to deserialize buffered event: {e}")

            return events

        except Exception as e:
            logger.error(f"Failed to retrieve pending events: {e}")
            return []

    def remove_event(self, event_id: str) -> bool:
        """
        Remove an event from the buffer (after successful send).

        Args:
            event_id: ID of event to remove

        Returns:
            True if removed, False if not found
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            cursor.execute(
                "DELETE FROM buffered_events WHERE event_id = ?",
                (event_id,)
            )

            deleted = cursor.rowcount > 0
            conn.commit()
            conn.close()

            if deleted:
                logger.info(f"Removed event {event_id} from buffer")

            return deleted

        except Exception as e:
            logger.error(f"Failed to remove event {event_id}: {e}")
            return False

    def update_retry(self, event_id: str, retry_count: int, error: Optional[str] = None):
        """
        Update retry state for a failed send.

        Args:
            event_id: ID of event
            retry_count: New retry count
            error: Error message from last attempt
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            cursor.execute(
                """
                UPDATE buffered_events
                SET retry_count = ?, last_error = ?, last_retry_at = CURRENT_TIMESTAMP
                WHERE event_id = ?
                """,
                (retry_count, error, event_id)
            )

            conn.commit()
            conn.close()

            logger.debug(f"Updated retry state for {event_id}: count={retry_count}")

        except Exception as e:
            logger.error(f"Failed to update retry for {event_id}: {e}")

    def get_stats(self) -> dict:
        """Get buffer statistics."""
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            cursor.execute("SELECT COUNT(*) FROM buffered_events")
            total = cursor.fetchone()[0]

            cursor.execute(
                "SELECT SUM(retry_count) FROM buffered_events"
            )
            total_retries = cursor.fetchone()[0] or 0

            cursor.execute(
                "SELECT AVG(retry_count) FROM buffered_events"
            )
            avg_retries = cursor.fetchone()[0] or 0.0

            conn.close()

            return {
                "total_buffered": total,
                "total_retries": total_retries,
                "avg_retries": round(avg_retries, 2)
            }

        except Exception as e:
            logger.error(f"Failed to get buffer stats: {e}")
            return {}

    def clear_old_events(self, max_age_hours: int = 24) -> int:
        """
        Clear events older than max_age_hours.

        Args:
            max_age_hours: Age threshold in hours

        Returns:
            Number of events deleted
        """
        try:
            conn = sqlite3.connect(self.db_path)
            cursor = conn.cursor()

            cursor.execute(
                """
                DELETE FROM buffered_events
                WHERE created_at < datetime('now', ? || ' hours')
                """,
                (f"-{max_age_hours}",)
            )

            deleted = cursor.rowcount
            conn.commit()
            conn.close()

            if deleted > 0:
                logger.info(f"Cleared {deleted} old events (age > {max_age_hours}h)")

            return deleted

        except Exception as e:
            logger.error(f"Failed to clear old events: {e}")
            return 0

