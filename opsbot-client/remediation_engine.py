"""
Remediation Engine for OpsBot client.

Executes deterministic remediation playbooks for common alerts.
No AI involved - purely programmatic with fallback to human escalation.

Features:
- Playbook-driven remediation
- Conditional logic based on signals/output
- Command execution with timeout & error handling
- Health checks with retry logic
- Alerting to Slack/PagerDuty on failure
- Idempotent actions (safe to repeat)
- Comprehensive action logging
"""

import asyncio
import json
import logging
import re
import subprocess
from typing import Any, Dict, List, Optional, Tuple
from dataclasses import dataclass
from datetime import datetime
import requests

from models import AlertEvent
from config import config

logger = logging.getLogger(__name__)


@dataclass
class RemediationAction:
    """Result of a single remediation action."""
    action_id: str
    action_type: str
    status: str  # success, failed, skipped
    description: str
    output: Optional[str] = None
    error: Optional[str] = None
    duration_seconds: float = 0.0
    timestamp: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "action_id": self.action_id,
            "action_type": self.action_type,
            "status": self.status,
            "description": self.description,
            "output": self.output[:200] if self.output else None,  # Truncate for logs
            "error": self.error[:200] if self.error else None,
            "duration_seconds": self.duration_seconds,
            "timestamp": self.timestamp
        }


@dataclass
class RemediationResult:
    """Overall result of remediation attempt."""
    alert_type: str
    success: bool
    actions: List[RemediationAction]
    escalated: bool
    escalation_reason: Optional[str] = None
    total_duration_seconds: float = 0.0
    timestamp: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "alert_type": self.alert_type,
            "success": self.success,
            "actions_executed": len(self.actions),
            "actions": [a.to_dict() for a in self.actions],
            "escalated": self.escalated,
            "escalation_reason": self.escalation_reason,
            "total_duration_seconds": self.total_duration_seconds,
            "timestamp": self.timestamp
        }


class RemediationEngine:
    """
    Executes deterministic remediation playbooks.

    Workflow:
    1. Load playbook for alert type
    2. Execute actions sequentially
    3. Check conditions & branch
    4. Verify fix worked
    5. Escalate if needed
    6. Log results
    """

    def __init__(self, playbook_file: str):
        """
        Initialize remediation engine.

        Args:
            playbook_file: Path to remediation_playbooks.json
        """
        self.playbooks = self._load_playbooks(playbook_file)
        self.execution_context: Dict[str, Any] = {}  # Store action outputs for re-use
        logger.info(f"Initialized RemediationEngine with {len(self.playbooks.get('remediations', {}))} playbooks")

    def _load_playbooks(self, file_path: str) -> Dict[str, Any]:
        """Load playbooks from JSON file."""
        try:
            with open(file_path, 'r') as f:
                playbooks = json.load(f)
            logger.info(f"Loaded remediation playbooks from {file_path}")
            return playbooks
        except Exception as e:
            logger.error(f"Failed to load playbooks from {file_path}: {e}")
            return {"remediations": {}}

    async def execute_remediation(self, alert: AlertEvent) -> RemediationResult:
        """
        Execute remediation for an alert.

        Args:
            alert: AlertEvent to remediate

        Returns:
            RemediationResult with actions taken and outcome
        """
        alert_type = alert.alert_type
        logger.info(f"Starting remediation for alert type: {alert_type}")

        # Reset execution context for this alert
        self.execution_context = {}

        # Get playbook
        playbooks = self.playbooks.get("remediations", {})
        if alert_type not in playbooks:
            logger.warning(f"No playbook found for alert type: {alert_type}")
            return RemediationResult(
                alert_type=alert_type,
                success=False,
                actions=[],
                escalated=True,
                escalation_reason=f"No playbook available for {alert_type}",
                timestamp=datetime.utcnow().isoformat()
            )

        playbook = playbooks[alert_type]
        actions_executed: List[RemediationAction] = []
        start_time = datetime.utcnow()

        try:
            # Execute actions in sequence
            for action_config in playbook.get("actions", []):
                action = await self._execute_action(action_config, alert)
                actions_executed.append(action)

                # Stop if critical action failed
                if action.status == "failed" and not action_config.get("optional", False):
                    logger.warning(f"Critical action {action.action_id} failed; stopping execution")
                    break

            # Determine overall success
            success = all(a.status in ["success", "skipped"] for a in actions_executed)
            escalated = not success

            end_time = datetime.utcnow()
            duration = (end_time - start_time).total_seconds()

            result = RemediationResult(
                alert_type=alert_type,
                success=success,
                actions=actions_executed,
                escalated=escalated,
                escalation_reason=None if success else "One or more remediation actions failed",
                total_duration_seconds=duration,
                timestamp=end_time.isoformat()
            )

            # Log & alert based on result
            await self._log_result(result, alert)

            return result

        except Exception as e:
            logger.error(f"Unexpected error during remediation: {e}", exc_info=True)
            return RemediationResult(
                alert_type=alert_type,
                success=False,
                actions=actions_executed,
                escalated=True,
                escalation_reason=str(e),
                timestamp=datetime.utcnow().isoformat()
            )

    async def _execute_action(self, action_config: Dict[str, Any], alert: AlertEvent) -> RemediationAction:
        """Execute a single action from the playbook."""
        action_id = action_config.get("id", "unknown")
        action_type = action_config.get("type", "unknown")
        description = action_config.get("description", "")
        start_time = datetime.utcnow()

        try:
            if action_type == "command":
                return await self._execute_command_action(action_config)

            elif action_type == "conditional":
                return await self._execute_conditional_action(action_config, alert)

            elif action_type == "wait":
                return await self._execute_wait_action(action_config)

            elif action_type == "verify":
                return await self._execute_verify_action(action_config)

            elif action_type == "health_check":
                return await self._execute_health_check_action(action_config)

            elif action_type == "alert":
                return await self._execute_alert_action(action_config, alert)

            else:
                logger.warning(f"Unknown action type: {action_type}")
                return RemediationAction(
                    action_id=action_id,
                    action_type=action_type,
                    status="skipped",
                    description=description,
                    error=f"Unknown action type: {action_type}",
                    duration_seconds=(datetime.utcnow() - start_time).total_seconds(),
                    timestamp=datetime.utcnow().isoformat()
                )

        except Exception as e:
            logger.error(f"Error executing action {action_id}: {e}", exc_info=True)
            return RemediationAction(
                action_id=action_id,
                action_type=action_type,
                status="failed",
                description=description,
                error=str(e),
                duration_seconds=(datetime.utcnow() - start_time).total_seconds(),
                timestamp=datetime.utcnow().isoformat()
            )

    async def _execute_command_action(self, action_config: Dict[str, Any]) -> RemediationAction:
        """Execute a shell command action."""
        action_id = action_config.get("id", "cmd")
        command = action_config.get("command", "")
        description = action_config.get("description", "")
        timeout = action_config.get("timeout_seconds", 30)
        requires_approval = action_config.get("requires_approval", False)

        logger.info(f"Executing command action [{action_id}]: {command[:100]}")

        # Check if approval needed
        if requires_approval:
            logger.warning(f"Action {action_id} requires approval (not implemented in auto-mode)")
            # In production, this would check a queue or user approval system
            # For now, we skip it
            return RemediationAction(
                action_id=action_id,
                action_type="command",
                status="skipped",
                description=description,
                output="Action requires manual approval",
                timestamp=datetime.utcnow().isoformat()
            )

        start_time = datetime.utcnow()

        try:
            process = await asyncio.create_subprocess_shell(
                command,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )

            try:
                stdout, stderr = await asyncio.wait_for(
                    process.communicate(),
                    timeout=timeout
                )
                output = stdout.decode('utf-8', errors='ignore')
                error = stderr.decode('utf-8', errors='ignore') if stderr else None

                # Store output for later conditional use
                if action_config.get("capture_output"):
                    self.execution_context[action_id] = output

                if process.returncode == 0:
                    logger.info(f"Command action {action_id} succeeded")
                    return RemediationAction(
                        action_id=action_id,
                        action_type="command",
                        status="success",
                        description=description,
                        output=output[:500],
                        duration_seconds=(datetime.utcnow() - start_time).total_seconds(),
                        timestamp=datetime.utcnow().isoformat()
                    )
                else:
                    logger.warning(f"Command action {action_id} failed with code {process.returncode}")
                    return RemediationAction(
                        action_id=action_id,
                        action_type="command",
                        status="failed",
                        description=description,
                        output=output[:500],
                        error=error[:500] if error else f"Exit code: {process.returncode}",
                        duration_seconds=(datetime.utcnow() - start_time).total_seconds(),
                        timestamp=datetime.utcnow().isoformat()
                    )

            except asyncio.TimeoutError:
                process.kill()
                logger.error(f"Command action {action_id} timed out after {timeout}s")
                return RemediationAction(
                    action_id=action_id,
                    action_type="command",
                    status="failed",
                    description=description,
                    error=f"Timeout after {timeout} seconds",
                    duration_seconds=(datetime.utcnow() - start_time).total_seconds(),
                    timestamp=datetime.utcnow().isoformat()
                )

        except Exception as e:
            logger.error(f"Error executing command: {e}")
            return RemediationAction(
                action_id=action_id,
                action_type="command",
                status="failed",
                description=description,
                error=str(e),
                duration_seconds=(datetime.utcnow() - start_time).total_seconds(),
                timestamp=datetime.utcnow().isoformat()
            )

    async def _execute_conditional_action(self, action_config: Dict[str, Any], alert: AlertEvent) -> RemediationAction:
        """Execute a conditional action (if/then/else branching)."""
        action_id = action_config.get("id", "cond")
        description = action_config.get("description", "")
        condition = action_config.get("condition", {})

        logger.info(f"Evaluating conditional action [{action_id}]: {description}")

        # Evaluate condition
        condition_met = self._evaluate_condition(condition, alert)
        logger.debug(f"Condition {action_id} evaluated to: {condition_met}")

        # Execute appropriate branch
        if condition_met:
            logger.info(f"Condition TRUE for {action_id}; executing 'then' branch")
            branch_actions = action_config.get("then", [])
        else:
            logger.info(f"Condition FALSE for {action_id}; executing 'else' branch")
            branch_actions = action_config.get("else", [])

        # Execute branch actions
        for sub_action in branch_actions:
            await self._execute_action(sub_action, alert)

        return RemediationAction(
            action_id=action_id,
            action_type="conditional",
            status="success",
            description=description,
            output=f"Branch executed: {'then' if condition_met else 'else'}",
            timestamp=datetime.utcnow().isoformat()
        )

    def _evaluate_condition(self, condition: Dict[str, Any], alert: AlertEvent) -> bool:
        """Evaluate a condition based on signals or output."""
        check_type = condition.get("check")

        # Check signal-based conditions
        signal_name = condition.get("signal")
        if signal_name:
            value = alert.signals.get(signal_name)
            operator = condition.get("operator")
            threshold = condition.get("threshold")

            if value is None:
                logger.warning(f"Signal {signal_name} not available")
                return False

            if operator == "greater_than":
                return float(value) > float(threshold)
            elif operator == "less_than":
                return float(value) < float(threshold)
            elif operator == "equals":
                return value == threshold
            else:
                logger.warning(f"Unknown operator: {operator}")
                return False

        # Check output-based conditions
        output_from = condition.get("output_from")
        if output_from and check_type == "contains":
            output = self.execution_context.get(output_from, "")
            pattern = condition.get("pattern", "")
            return pattern in output

        if output_from and check_type == "contains_any_process":
            output = self.execution_context.get(output_from, "")
            patterns = condition.get("patterns", [])
            return any(p in output for p in patterns)

        if output_from and check_type == "failed":
            # Check if previous action failed
            return output_from not in self.execution_context or not self.execution_context[output_from]

        return False

    async def _execute_wait_action(self, action_config: Dict[str, Any]) -> RemediationAction:
        """Execute a wait action."""
        action_id = action_config.get("id", "wait")
        description = action_config.get("description", "")
        wait_seconds = action_config.get("seconds", 5)

        logger.info(f"Waiting {wait_seconds} seconds: {description}")
        await asyncio.sleep(wait_seconds)

        return RemediationAction(
            action_id=action_id,
            action_type="wait",
            status="success",
            description=description,
            duration_seconds=wait_seconds,
            timestamp=datetime.utcnow().isoformat()
        )

    async def _execute_verify_action(self, action_config: Dict[str, Any]) -> RemediationAction:
        """Execute a verification action (check if fix worked)."""
        action_id = action_config.get("id", "verify")
        command = action_config.get("command", "")
        description = action_config.get("description", "")
        expected_pattern = action_config.get("expected_pattern", "")
        timeout = action_config.get("timeout_seconds", 30)
        max_retries = action_config.get("max_retries", 1)
        retry_interval = action_config.get("retry_interval_seconds", 2)

        logger.info(f"Verifying remediation [{action_id}]: {description}")

        for attempt in range(max_retries):
            try:
                process = await asyncio.create_subprocess_shell(
                    command,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE
                )

                stdout, _ = await asyncio.wait_for(
                    process.communicate(),
                    timeout=timeout
                )
                output = stdout.decode('utf-8', errors='ignore')

                # Check if output matches expected pattern
                if re.search(expected_pattern, output):
                    logger.info(f"Verification {action_id} passed")
                    return RemediationAction(
                        action_id=action_id,
                        action_type="verify",
                        status="success",
                        description=description,
                        output=output[:200],
                        timestamp=datetime.utcnow().isoformat()
                    )

                # Pattern didn't match; retry if available
                if attempt < max_retries - 1:
                    logger.warning(f"Verification {action_id} attempt {attempt + 1} failed; retrying...")
                    await asyncio.sleep(retry_interval)
                    continue

            except asyncio.TimeoutError:
                logger.warn(f"Verification {action_id} timed out")
                if attempt < max_retries - 1:
                    await asyncio.sleep(retry_interval)
                    continue

            except Exception as e:
                logger.error(f"Error during verification: {e}")

        # All retries exhausted
        logger.error(f"Verification {action_id} failed after {max_retries} attempts")
        return RemediationAction(
            action_id=action_id,
            action_type="verify",
            status="failed",
            description=description,
            error=f"Expected pattern not found: {expected_pattern}",
            timestamp=datetime.utcnow().isoformat()
        )

    async def _execute_health_check_action(self, action_config: Dict[str, Any]) -> RemediationAction:
        """Execute health check (HTTP request with retries)."""
        action_id = action_config.get("id", "health_check")
        url = action_config.get("url", "")
        method = action_config.get("method", "GET")
        expected_status = action_config.get("expected_status", 200)
        timeout = action_config.get("timeout_seconds", 10)
        max_retries = action_config.get("max_retries", 3)
        retry_interval = action_config.get("retry_interval_seconds", 2)
        description = action_config.get("description", "")

        logger.info(f"Health checking [{action_id}]: {url}")

        for attempt in range(max_retries):
            try:
                response = requests.request(
                    method=method,
                    url=url,
                    timeout=timeout
                )

                if response.status_code == expected_status:
                    logger.info(f"Health check {action_id} passed (status {response.status_code})")
                    return RemediationAction(
                        action_id=action_id,
                        action_type="health_check",
                        status="success",
                        description=description,
                        output=f"Status {response.status_code}",
                        timestamp=datetime.utcnow().isoformat()
                    )

                # Status mismatch; retry
                if attempt < max_retries - 1:
                    logger.warning(f"Health check {action_id} got {response.status_code}; retrying...")
                    await asyncio.sleep(retry_interval)
                    continue

            except requests.exceptions.Timeout:
                logger.warning(f"Health check {action_id} timed out; retrying...")
                if attempt < max_retries - 1:
                    await asyncio.sleep(retry_interval)
                    continue

            except Exception as e:
                logger.warning(f"Health check {action_id} error: {e}; retrying...")
                if attempt < max_retries - 1:
                    await asyncio.sleep(retry_interval)
                    continue

        # All retries exhausted
        logger.error(f"Health check {action_id} failed after {max_retries} attempts")
        return RemediationAction(
            action_id=action_id,
            action_type="health_check",
            status="failed",
            description=description,
            error=f"Did not get expected status {expected_status}",
            timestamp=datetime.utcnow().isoformat()
        )

    async def _execute_alert_action(self, action_config: Dict[str, Any], alert: AlertEvent) -> RemediationAction:
        """Execute an alert action (send to Slack/PagerDuty)."""
        action_id = action_config.get("id", "alert")
        channel = action_config.get("channel", "slack")
        severity = action_config.get("severity", "MEDIUM")
        message = action_config.get("message", "")
        description = action_config.get("description", "")

        logger.info(f"Sending alert [{action_id}] to {channel}: {message}")

        # Format alert message
        alert_message = f"[{severity}] {message}\nAlert Type: {alert.alert_type}\nSource: {alert.source}\nService: {alert.service}"

        try:
            if channel == "slack":
                await self._send_slack_alert(alert_message, severity)
            elif channel == "pagerduty":
                await self._send_pagerduty_alert(alert_message, severity, alert)

            return RemediationAction(
                action_id=action_id,
                action_type="alert",
                status="success",
                description=description,
                output=f"Alert sent to {channel}",
                timestamp=datetime.utcnow().isoformat()
            )

        except Exception as e:
            logger.error(f"Failed to send alert to {channel}: {e}")
            return RemediationAction(
                action_id=action_id,
                action_type="alert",
                status="failed",
                description=description,
                error=str(e),
                timestamp=datetime.utcnow().isoformat()
            )

    async def _send_slack_alert(self, message: str, severity: str):
        """Send alert to Slack (if configured)."""
        # Get Slack webhook from config or environment
        slack_webhook = getattr(config, "slack_webhook_url", None)
        if not slack_webhook:
            logger.debug("Slack webhook not configured; skipping")
            return

        color_map = {
            "LOW": "#36a64f",
            "MEDIUM": "#ff9900",
            "HIGH": "#ff6600",
            "CRITICAL": "#ff0000"
        }

        payload = {
            "blocks": [
                {
                    "type": "section",
                    "text": {
                        "type": "mrkdwn",
                        "text": message
                    }
                }
            ]
        }

        try:
            response = requests.post(slack_webhook, json=payload, timeout=5)
            if response.status_code != 200:
                logger.warning(f"Slack alert failed with status {response.status_code}")
        except Exception as e:
            logger.error(f"Error sending Slack alert: {e}")

    async def _send_pagerduty_alert(self, message: str, severity: str, alert: AlertEvent):
        """Send alert to PagerDuty (if configured)."""
        # Get PagerDuty config
        pd_integration_key = getattr(config, "pagerduty_integration_key", None)
        if not pd_integration_key:
            logger.debug("PagerDuty integration key not configured; skipping")
            return

        # PagerDuty Event API v2
        payload = {
            "routing_key": pd_integration_key,
            "event_action": "trigger",
            "dedup_key": f"opsbot-{alert.event_id}",
            "payload": {
                "summary": f"[{severity}] {alert.alert_type} on {alert.source}",
                "severity": severity.lower(),
                "source": alert.source,
                "custom_details": {
                    "alert_type": alert.alert_type,
                    "service": alert.service,
                    "signals": alert.signals
                }
            }
        }

        try:
            response = requests.post(
                "https://events.pagerduty.com/v2/enqueue",
                json=payload,
                timeout=5
            )
            if response.status_code != 202:
                logger.warning(f"PagerDuty alert failed with status {response.status_code}")
        except Exception as e:
            logger.error(f"Error sending PagerDuty alert: {e}")

    async def _log_result(self, result: RemediationResult, alert: AlertEvent):
        """Log remediation result and send notifications."""
        logger.info(f"Remediation completed for {result.alert_type}: success={result.success}")
        logger.debug(f"Remediation result:\n{json.dumps(result.to_dict(), indent=2)}")

        # Send notifications based on config
        defaults = self.playbooks.get("defaults", {})
        notify_attempt = defaults.get("notify_on_remediation_attempt", True)
        notify_success = defaults.get("notify_on_remediation_success", True)
        notify_failure = defaults.get("notify_on_remediation_failure", True)

        if notify_attempt or (result.success and notify_success) or (not result.success and notify_failure):
            status_str = "SUCCESS" if result.success else "FAILED"
            message = f"Remediation {status_str}: {alert.alert_type}\nDuration: {result.total_duration_seconds:.2f}s"

            if result.escalation_reason:
                message += f"\nEscalation: {result.escalation_reason}"

            try:
                await self._send_slack_alert(message, "HIGH" if not result.success else "MEDIUM")
            except Exception as e:
                logger.error(f"Error sending notification: {e}")

