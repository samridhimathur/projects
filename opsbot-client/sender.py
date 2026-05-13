"""
HTTP sender for delivering alert events to cloud API.

Implements retry logic with exponential backoff.
Integrates with local buffer for offline resilience.
"""

import requests
import asyncio
import logging
from typing import Optional, Tuple
from datetime import datetime, timedelta
import time
import json

from models import AlertEvent
from buffer import EventBuffer
from config import config

logger = logging.getLogger(__name__)


class CloudAPISender:
    """
    Sends alert events to cloud ingestion API.

    Features:
    - Retry with exponential backoff
    - Local buffering on failure
    - Connection error handling
    - Request timeout handling
    """

    def __init__(self, buffer: EventBuffer):
        """
        Initialize sender.

        Args:
            buffer: EventBuffer for offline persistence
        """
        self.buffer = buffer
        self.session = requests.Session()
        self.base_url = config.cloud_url
        self.ingestion_path = config.ingestion_path
        self.ingestion_url = f"{self.base_url}{self.ingestion_path}"

        logger.info(f"Initialized CloudAPISender: {self.ingestion_url}")

    async def send_event(self, event: AlertEvent) -> Tuple[bool, Optional[str]]:
        """
        Send an alert event to cloud API.

        Implements retry with exponential backoff:
        - Retry 1: immediate
        - Retry 2: wait 2 seconds
        - Retry 3: wait 4 seconds
        - Retry 4: wait 8 seconds

        On final failure, buffers event for later replay.

        Args:
            event: AlertEvent to send

        Returns:
            (success: bool, error_message: Optional[str])
        """
        for attempt in range(1, config.retry_max_attempts + 1):
            try:
                logger.debug(f"Sending event {event.event_id} (attempt {attempt}/{config.retry_max_attempts})")

                # Make HTTP POST request
                response = self.session.post(
                    self.ingestion_url,
                    json=json.loads(event.model_dump_json(by_alias=True)),
                    timeout=10,
                    headers={"Content-Type": "application/json"}
                )

                # Check response status
                if response.status_code in [200, 202]:  # 202 is Accepted
                    logger.info(f"Event {event.event_id} sent successfully (status: {response.status_code})")
                    return True, None

                elif response.status_code == 400:
                    error_msg = response.json().get("error", "Bad request")
                    logger.warning(f"Event {event.event_id} validation failed: {error_msg}")
                    return False, f"Validation error: {error_msg}"

                elif response.status_code >= 500:
                    error_msg = f"Server error: {response.status_code}"
                    logger.warning(f"Temporary server error for {event.event_id}: {error_msg}")

                    if attempt < config.retry_max_attempts:
                        wait_time = config.retry_backoff_seconds ** (attempt - 1)
                        logger.info(f"Retrying in {wait_time} seconds...")
                        await asyncio.sleep(wait_time)
                        continue
                    else:
                        return False, error_msg

                else:
                    error_msg = f"HTTP {response.status_code}"
                    logger.error(f"Unexpected response for {event.event_id}: {error_msg}")
                    return False, error_msg

            except requests.exceptions.Timeout:
                error_msg = "Request timeout"
                logger.warning(f"Timeout sending {event.event_id}")

                if attempt < config.retry_max_attempts:
                    wait_time = config.retry_backoff_seconds ** (attempt - 1)
                    logger.info(f"Retrying in {wait_time} seconds...")
                    await asyncio.sleep(wait_time)
                    continue
                else:
                    return False, error_msg

            except requests.exceptions.ConnectionError:
                error_msg = "Connection error"
                logger.warning(f"Connection error sending {event.event_id}")

                if attempt < config.retry_max_attempts:
                    wait_time = config.retry_backoff_seconds ** (attempt - 1)
                    logger.info(f"Retrying in {wait_time} seconds...")
                    await asyncio.sleep(wait_time)
                    continue
                else:
                    return False, error_msg

            except Exception as e:
                error_msg = str(e)
                logger.error(f"Unexpected error sending {event.event_id}: {error_msg}", exc_info=True)
                return False, error_msg

        # All retries exhausted
        logger.error(f"Failed to send event {event.event_id} after {config.retry_max_attempts} attempts")
        return False, "All retries exhausted"

    async def send_event_with_buffer(self, event: AlertEvent) -> bool:
        """
        Send event to cloud, buffering on failure.

        Args:
            event: AlertEvent to send

        Returns:
            True if sent successfully, False if buffered
        """
        success, error = await self.send_event(event)

        if not success:
            logger.info(f"Buffering failed event {event.event_id}: {error}")
            self.buffer.add_event(event)
            return False

        return True

    async def replay_buffered_events(self) -> Tuple[int, int]:
        """
        Replay events from buffer.

        Processes pending events in batches, retrying failed ones.

        Returns:
            (successful_count, failed_count)
        """
        pending = self.buffer.get_pending_events(limit=100)

        if not pending:
            logger.debug("No buffered events to replay")
            return 0, 0

        logger.info(f"Replaying {len(pending)} buffered events")

        successful = 0
        failed = 0

        for event in pending:
            success, error = await self.send_event(event)

            if success:
                self.buffer.remove_event(event.event_id)
                successful += 1
            else:
                retry_count = self.buffer.get_pending_events()
                # Count retries by looking at stats
                self.buffer.update_retry(event.event_id, 1, error)
                failed += 1

        logger.info(f"Replay completed: {successful} successful, {failed} failed")
        return successful, failed

    async def poll_and_send(self):
        """
        Continuous polling loop to replay buffered events.

        Checks buffer every 60 seconds and attempts replay.
        """
        while True:
            try:
                stats = self.buffer.get_stats()

                if stats.get("total_buffered", 0) > 0:
                    logger.info(f"Buffer stats: {stats}")
                    successful, failed = await self.replay_buffered_events()

                await asyncio.sleep(60)  # Check every 60 seconds

            except Exception as e:
                logger.error(f"Error in poll_and_send loop: {e}", exc_info=True)
                await asyncio.sleep(60)

