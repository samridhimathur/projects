#!/usr/bin/env python3
"""
Test script for OpsBot Client Remediation Engine.

Demonstrates how the remediation engine handles different alert types.
Run: python test_remediation.py
"""

import asyncio
import json
import logging
import sys
from datetime import datetime

# Add parent directory to path
sys.path.insert(0, '.')

from remediation_engine import RemediationEngine
from models import AlertEvent

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


async def test_high_cpu_remediation():
    """Test high CPU remediation."""
    print("\n" + "="*80)
    print("TEST 1: High CPU Remediation")
    print("="*80)

    engine = RemediationEngine("./remediation_playbooks.json")

    alert = AlertEvent(
        source="test-client-01",
        service="api-gateway",
        alert_type="high_cpu",
        severity="HIGH",
        detected_at_ms=int(datetime.utcnow().timestamp() * 1000),
        signals={
            "cpu_percent": 92.5,
            "memory_percent": 45.2,
            "disk_percent": 62.1,
            "error_count": 5
        }
    )

    logger.info(f"Testing remediation for: {alert.alert_type}")
    logger.info(f"Signals: CPU={alert.signals['cpu_percent']}%, Memory={alert.signals['memory_percent']}%")

    result = await engine.execute_remediation(alert)

    print(f"\nResult: {json.dumps(result.to_dict(), indent=2)}")
    print(f"✓ Remediation completed in {result.total_duration_seconds:.2f}s")
    print(f"✓ Success: {result.success}")
    print(f"✓ Actions executed: {len(result.actions)}")

    for action in result.actions:
        status_icon = "✓" if action.status == "success" else "✗" if action.status == "failed" else "⊘"
        print(f"  {status_icon} [{action.action_type}] {action.action_id}: {action.status}")


async def test_high_memory_remediation():
    """Test high memory remediation."""
    print("\n" + "="*80)
    print("TEST 2: High Memory Remediation")
    print("="*80)

    engine = RemediationEngine("./remediation_playbooks.json")

    alert = AlertEvent(
        source="test-client-01",
        service="django-app",
        alert_type="high_memory",
        severity="HIGH",
        detected_at_ms=int(datetime.utcnow().timestamp() * 1000),
        signals={
            "cpu_percent": 25.3,
            "memory_percent": 91.2,
            "memory_mb": 14654,
            "disk_percent": 62.1
        }
    )

    logger.info(f"Testing remediation for: {alert.alert_type}")
    logger.info(f"Signals: Memory={alert.signals['memory_percent']}%")

    result = await engine.execute_remediation(alert)

    print(f"\nResult Summary:")
    print(f"✓ Remediation completed in {result.total_duration_seconds:.2f}s")
    print(f"✓ Success: {result.success}")
    print(f"✓ Escalated: {result.escalated}")
    if result.escalation_reason:
        print(f"✓ Reason: {result.escalation_reason}")


async def test_service_unhealthy_remediation():
    """Test service health check remediation."""
    print("\n" + "="*80)
    print("TEST 3: Service Unhealthy Remediation")
    print("="*80)

    engine = RemediationEngine("./remediation_playbooks.json")

    alert = AlertEvent(
        source="test-client-01",
        service="nginx",
        alert_type="service_unhealthy",
        severity="CRITICAL",
        detected_at_ms=int(datetime.utcnow().timestamp() * 1000),
        signals={
            "service_healthy": False,
            "response_time_p99_ms": 0
        }
    )

    logger.info(f"Testing remediation for: {alert.alert_type}")
    logger.info(f"Signals: Service is unhealthy")

    result = await engine.execute_remediation(alert)

    print(f"\nResult Summary:")
    print(f"✓ Remediation completed in {result.total_duration_seconds:.2f}s")
    print(f"✓ Success: {result.success}")
    print(f"✓ Escalated: {result.escalated}")


async def test_unknown_alert_type():
    """Test handling of unknown alert type."""
    print("\n" + "="*80)
    print("TEST 4: Unknown Alert Type (No Playbook)")
    print("="*80)

    engine = RemediationEngine("./remediation_playbooks.json")

    alert = AlertEvent(
        source="test-client-01",
        service="unknown-service",
        alert_type="unknown_anomaly",
        severity="MEDIUM",
        detected_at_ms=int(datetime.utcnow().timestamp() * 1000),
        signals={"unknown_metric": 99.9}
    )

    logger.info(f"Testing remediation for: {alert.alert_type}")

    result = await engine.execute_remediation(alert)

    print(f"\nResult Summary:")
    print(f"✓ Remediation completed in {result.total_duration_seconds:.2f}s")
    print(f"✓ Success: {result.success}")
    print(f"✗ Escalated: {result.escalated} (expected, no playbook)")
    print(f"✓ Reason: {result.escalation_reason}")


async def test_playbook_validation():
    """Validate playbook structure."""
    print("\n" + "="*80)
    print("TEST 5: Playbook Validation")
    print("="*80)

    try:
        with open("./remediation_playbooks.json", 'r') as f:
            playbooks = json.load(f)

        print(f"✓ Playbook file is valid JSON")

        remediations = playbooks.get("remediations", {})
        print(f"✓ Found {len(remediations)} remediation playbooks")

        for alert_type, playbook in remediations.items():
            actions = playbook.get("actions", [])
            description = playbook.get("description", "")
            severity = playbook.get("severity", "")
            print(f"  ✓ {alert_type}: {description} ({len(actions)} actions, severity={severity})")

            for action in actions:
                action_id = action.get("id", "?")
                action_type = action.get("type", "?")
                print(f"    • {action_id} ({action_type})")

        print(f"\n✓ Playbook structure is valid")

    except json.JSONDecodeError as e:
        print(f"✗ Playbook JSON is invalid: {e}")
    except FileNotFoundError:
        print(f"✗ Playbook file not found: ./remediation_playbooks.json")


async def main():
    """Run all tests."""
    print("\n" + "█" * 80)
    print("█" + " " * 78 + "█")
    print("█" + "  OpsBot Client - Remediation Engine Test Suite".center(78) + "█")
    print("█" + " " * 78 + "█")
    print("█" * 80)

    try:
        # Validate playbook first
        await test_playbook_validation()

        # Run remediation tests
        await test_high_cpu_remediation()
        await test_high_memory_remediation()
        await test_service_unhealthy_remediation()
        await test_unknown_alert_type()

        print("\n" + "█" * 80)
        print("█" + " " * 78 + "█")
        print("█" + "  All tests completed!".center(78) + "█")
        print("█" + " " * 78 + "█")
        print("█" * 80 + "\n")

    except Exception as e:
        logger.error(f"Test suite failed: {e}", exc_info=True)
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())

