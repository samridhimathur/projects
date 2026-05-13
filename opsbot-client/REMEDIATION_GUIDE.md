# Client-Side Remediation Engine

## Overview

The OpsBot Client includes a **deterministic, playbook-driven remediation engine** that automatically responds to common anomalies detected on the client machine.

### Key Principles

- ✅ **No AI** - Purely deterministic logic (no LLM)
- ✅ **Fast** - Executes in < 1 second (no waiting for cloud)
- ✅ **Safe** - Only runs pre-defined, low-risk actions
- ✅ **Idempotent** - Safe to repeat multiple times
- ✅ **Observable** - All actions logged and tracked
- ✅ **Offline-Capable** - Works without cloud connectivity

---

## Architecture

```
Alert Detected (high_cpu, high_memory, etc.)
        ↓
Rule Engine (deterministic threshold check)
        ↓
Remediation Engine (parallel with cloud send)
        ├─ Load playbook
        ├─ Execute actions sequentially
        ├─ Check conditions
        ├─ Branch on if/then/else
        ├─ Verify fix worked
        └─ Log results + Slack/PagerDuty alert
        ↓
Cloud RCA (parallel: for intelligent root cause analysis)
```

---

## Supported Alert Types

### 1. `high_cpu` - CPU Usage Exceeds Threshold

**Scenario:** Process consuming > 85% CPU

**Automatic Actions:**
1. Identify top 5 CPU-consuming processes
2. Check if top process is a known "rogue" process (stress test, memory hog, etc.)
3. If yes: Kill it (safe known process)
4. If no: Alert operator for manual investigation
5. Verify CPU usage dropped
6. Log results

**Example:**
```bash
# Before remediation
$ top -n1 | grep python
python stress   1234  89.5%  ...

# Remediation executes
$ pkill -9 -f 'python stress'

# After remediation
$ top -n1 | grep Cpu
Cpu(s):  25.3%us,  4.2%sy, ...  (recovered)
```

---

### 2. `high_memory` - Memory Usage Exceeds Threshold

**Scenario:** System memory > 80%

**Automatic Actions:**
1. Check memory hog process
2. If memory > 90%: Restart service (nginx, app, etc.)
3. If memory 80-90%: Alert operator
4. Wait for service to stabilize
5. Verify memory recovered
6. Log results

**Example:**
```bash
# Before
$ free | grep Mem
Mem:   16384   15000    ...  (93% used)

# Remediation executes
$ systemctl restart nginx

# After
$ free | grep Mem
Mem:   16384    4500    ...  (27% used)
```

---

### 3. `service_unhealthy` - Service Health Check Failed

**Scenario:** Service not responding to health checks

**Automatic Actions:**
1. Log current service status
2. Attempt graceful restart
3. Wait for service to come up
4. Perform health check (HTTP GET /health)
5. If health check passes: Success ✓
6. If still unhealthy: Escalate to PagerDuty (page on-call)

**Example:**
```bash
# Before
$ curl http://localhost:8080/health
Connection refused

# Remediation executes
$ systemctl restart app
$ sleep 5
$ curl http://localhost:8080/health
{"status":"UP"}  # Success!
```

---

### 4. `log_error_spike` - Error Log Spike Detected

**Scenario:** Sudden increase in ERROR lines in logs

**Automatic Actions:**
1. Capture recent error logs
2. Check if database connection issue
3. If "Connection refused" in logs: Restart DB connection pool
4. If other error: Alert operator with log excerpt
5. Verify error rate decreased
6. Log results

---

## Playbook Structure

Playbooks are defined in **`remediation_playbooks.json`** with a simple structure:

```json
{
  "remediations": {
    "alert_type_name": {
      "description": "Human-readable description",
      "severity": "HIGH",
      "actions": [
        {
          "id": "action_id",
          "type": "command|conditional|wait|verify|health_check|alert",
          "description": "What this step does",
          ...action-specific fields...
        }
      ]
    }
  }
}
```

### Action Types

#### **type: "command"** - Execute shell command

```json
{
  "type": "command",
  "command": "ps aux --sort=-%cpu | head -5",
  "description": "Get top CPU processes",
  "timeout_seconds": 5,
  "capture_output": true,
  "requires_approval": false
}
```

**Fields:**
- `command`: Shell command to execute
- `timeout_seconds`: Max execution time
- `capture_output`: Store output for later use in conditions
- `requires_approval`: If true, require manual approval (future)

#### **type: "conditional"** - If/then/else branching

```json
{
  "type": "conditional",
  "condition": {
    "signal": "cpu_percent",
    "operator": "greater_than",
    "threshold": 90
  },
  "then": [
    {"type": "command", "command": "..."}
  ],
  "else": [
    {"type": "alert", "channel": "slack", "message": "..."}
  ]
}
```

**Condition Types:**
- `signal` + `operator` + `threshold`: Compare alert signals
  - Operators: `greater_than`, `less_than`, `equals`
- `output_from`: Check output of a previous action
  - Checks: `contains`, `contains_any_process`, `failed`

#### **type: "wait"** - Pause execution

```json
{
  "type": "wait",
  "seconds": 5,
  "description": "Allow service to stabilize"
}
```

#### **type: "verify"** - Verify fix worked

```json
{
  "type": "verify",
  "command": "top -b -n1 | grep Cpu",
  "expected_pattern": "Cpu.*[0-7][0-9]\\.[0-9]%",
  "timeout_seconds": 30,
  "max_retries": 3,
  "retry_interval_seconds": 2,
  "description": "Verify CPU < 80%"
}
```

Uses regex pattern matching to verify command output. Retries if pattern not found.

#### **type: "health_check"** - HTTP health check

```json
{
  "type": "health_check",
  "url": "http://localhost:8080/health",
  "method": "GET",
  "expected_status": 200,
  "timeout_seconds": 10,
  "max_retries": 3,
  "retry_interval_seconds": 2,
  "description": "Verify service is healthy"
}
```

#### **type: "alert"** - Send alert to operator

```json
{
  "type": "alert",
  "channel": "slack|pagerduty",
  "severity": "LOW|MEDIUM|HIGH|CRITICAL",
  "message": "Human-readable message",
  "urgency": "high"  (for PagerDuty)
}
```

---

## Usage

### Basic Flow

1. **Alert fires** (e.g., CPU > 85%)
2. **Rule engine detects** anomaly
3. **Remediation engine loads playbook** for `high_cpu`
4. **Executes actions** sequentially
5. **Verifies fix** worked
6. **Logs results** to stdout + Slack/PagerDuty
7. **Returns result** to main loop
8. **Cloud RCA** runs in parallel (no blocking)

### Integration with Main Loop

The client automatically runs remediation when a local alert fires:

```python
# In opsbot_client/main.py

# ...in monitoring loop...
for alert in detection_result.anomalies_detected:
    # Execute local remediation
    remediation_result = await self.remediation_engine.execute_remediation(alert)
    
    if remediation_result.success:
        logger.info(f"Remediation SUCCESS: {alert.alert_type}")
    else:
        logger.warning(f"Remediation FAILED: {remediation_result.escalation_reason}")
    
    # Send to cloud (parallel, non-blocking)
    await self.sender.send_event_with_buffer(alert)
```

---

## Configuration

### Environment Variables

```bash
# Path to remediation playbooks (default: ./remediation_playbooks.json)
export OPSBOT_REMEDIATION_PLAYBOOKS=/etc/opsbot/remediation_playbooks.json

# Slack webhook for alerts (optional)
export SLACK_WEBHOOK_URL=https://hooks.slack.com/services/YOUR/WEBHOOK/URL

# PagerDuty integration key (optional)
export PAGERDUTY_INTEGRATION_KEY=your-integration-key
```

### .env File

```bash
# opsbot-client/.env
OPSBOT_REMEDIATION_PLAYBOOKS=./remediation_playbooks.json
SLACK_WEBHOOK_URL=https://hooks.slack.com/services/YOUR/WEBHOOK/URL
PAGERDUTY_INTEGRATION_KEY=your-integration-key
```

---

## Examples

### Example 1: High CPU Auto-Remediation

**Alert:** CPU usage jumps to 92%

**What happens:**1. Playbook loads: `high_cpu`
2. Step 1: `ps aux --sort=-%cpu | head -5`
   - Output: `python stress 1234 89.5%` (known test process)
3. Step 2: Condition check: Is it a known rogue process?
   - Result: YES (matches pattern "python stress")
4. Step 3: Execute remediation: `pkill -9 -f 'python stress'`
   - Kill PID 1234
5. Step 4: Wait 3 seconds for cleanup
6. Step 5: Verify `top -b -n1 | grep Cpu` matches pattern `< 80%`
   - Result: CPU now 35% ✓
7. Log success and send Slack notification

**Result:**
```
✓ Remediation SUCCESS: high_cpu
  Duration: 0.85 seconds
  Actions: gather_diagnostics, check_rogue_process, kill_rogue_process, wait, verify_cpu_recovered
  Status: All actions succeeded. CPU recovered from 92% to 35%.
```

---

### Example 2: Memory Recovery

**Alert:** Memory usage 91%

**What happens:**
1. Playbook loads: `high_memory`
2. Step 1: Check memory hog: `ps aux --sort=-%mem | head -3`
3. Step 2: Condition check: Memory > 90%?
   - Result: YES
4. Step 3: Execute remediation: `systemctl restart nginx`
5. Step 4: Wait 5 seconds for service startup
6. Step 5: Verify `free | grep Mem` shows low usage
   - Result: Memory now 28% ✓
7. Log success and send Slack notification

**Result:**
```
✓ Remediation SUCCESS: high_memory
  Duration: 7.2 seconds
  Status: nginx restarted, memory recovered from 91% to 28%
```

---

### Example 3: Service Unhealthy (Failed, Escalates)

**Alert:** Service health check failed

**What happens:**
1. Playbook loads: `service_unhealthy`
2. Step 1: Log service status
3. Step 2: Execute remediation: `systemctl restart app`
4. Step 3: Wait 5 seconds
5. Step 4: Health check `http://localhost:8080/health`
   - Attempt 1: Connection refused
   - Attempt 2: Connection refused
   - Attempt 3: Connection refused
   - **FAILED** after 3 retries
6. Step 5: Escalate condition met
   - Send critical alert to PagerDuty (page on-call)
7. Log failure with escalation reason

**Result:**
```
✗ Remediation FAILED: service_unhealthy
  Duration: 18.3 seconds
  Status: Service restart failed. Escalating to on-call team.
  PagerDuty: Critical alert sent (incident created)
```

---

## Best Practices

### For Remediating Your Own Playbooks

1. **Start Simple** - Begin with commands that have no side effects
   ```json
   {"type": "command", "command": "curl http://localhost:8080/health"}
   ```

2. **Always Verify** - End each remediation with a verification step
   ```json
   {"type": "verify", "command": "...", "expected_pattern": "..."}
   ```

3. **Escalate on Failure** - If remediation fails, alert human operators
   ```json
   {"type": "alert", "channel": "pagerduty", "severity": "CRITICAL"}
   ```

4. **Use Idempotent Commands** - Commands safe to run multiple times
   ```bash
   # Good (idempotent)
   systemctl restart nginx   # Safe to run 5x

   # Bad (not idempotent)
   rm -rf /var/log/app.log   # Only safe first time!
   ```

5. **Add Conditions** - Only remediate if specific signals met
   ```json
   {"type": "conditional", "condition": {"signal": "memory_percent", "operator": "greater_than", "threshold": 90}}
   ```

6. **Require Approval for Risky Actions** - Gate dangerous operations
   ```json
   {"requires_approval": true, "command": "systemctl stop postgres"}
   ```

7. **Add Timeouts** - Prevent hanging commands
   ```json
   {"timeout_seconds": 10}  # Fail if takes > 10s
   ```

---

## Monitoring & Validation

### View Client Logs

```bash
# Client-side logs
tail -f opsbot_client.log | grep "Remediation"

# Example output
INFO - Remediation for alert type: high_cpu
INFO - Executing command action [gather_diagnostics]: ps aux --sort=-%cpu | head -5
INFO - Rule high_cpu matched! Remediation starting
INFO - Command action gather_diagnostics succeeded
INFO - Evaluating conditional action [check_rogue_process]
INFO - Condition TRUE for check_rogue_process; executing 'then' branch
INFO - Command action kill_rogue_process succeeded
INFO - Verification high_cpu passed
INFO - Remediation completed for high_cpu: success=true
```

### Check Remediation Results in Buffer

```python
# Python
from opsbot_client.buffer import EventBuffer
buffer = EventBuffer()
events = buffer.get_pending_events(limit=10)

for event in events:
    print(f"Event: {event.alert_type}")
    print(f"  Signals: {event.signals}")
    print(f"  Metadata: {event.metadata}")
```

### Verify in Cloud Logs

Remediation results are stored as metadata in cloud:

```bash
# Cloud logs
docker logs opsbot-app | grep "remediation"

# Kafka message contains remediation detail
{
  "eventId": "...",
  "alert_type": "high_cpu",
  "metadata": {
    "rule_name": "high_cpu",
    "remediation_status": "success",
    "remediation_duration_seconds": 0.85,
    "actions_executed": 5
  }
}
```

---

## Advanced: Customizing Playbooks

### Example: Add Custom Alert Type

1. **Add rule to `rules.json`:**
   ```json
   {
     "rule_name": "disk_full",
     "alert_type": "disk_full",
     "severity": "CRITICAL",
     "metric_name": "disk_percent",
     "operator": "greater_than",
     "threshold": 95,
     "cooldown_seconds": 600
   }
   ```

2. **Add playbook to `remediation_playbooks.json`:**
   ```json
   "disk_full": {
     "description": "Handle critical disk usage",
     "severity": "CRITICAL",
     "actions": [
       {
         "type": "command",
         "command": "du -sh /* | sort -rh | head -5",
         "capture_output": true
       },
       {
         "type": "alert",
         "channel": "pagerduty",
         "severity": "CRITICAL",
         "message": "Disk nearly full. Manual cleanup required."
       }
     ]
   }
   ```

3. **Restart client:**
   ```bash
   pkill -f "python -m opsbot_client"
   python -m opsbot_client.main
   ```

---

## Troubleshooting

### Remediation Not Running

**Check 1:** Playbook file exists
```bash
ls -la remediation_playbooks.json
```

**Check 2:** Flask playbook is valid JSON
```bash
python -m json.tool remediation_playbooks.json > /dev/null && echo "Valid JSON" || echo "Invalid JSON!"
```

**Check 3:** Alert type is defined in playbook
```bash
grep "high_cpu" remediation_playbooks.json
```

### Commands Timing Out

**Issue:** Command taking longer than `timeout_seconds`

**Solution:** Increase timeout
```json
{"timeout_seconds": 60}  # Increase from 30 to 60
```

### Verification Not Passing

**Issue:** Regex pattern not matching output

**Debug:**
```bash
# Manually run the verify command
top -b -n1 | grep Cpu

# Check the pattern
echo "Cpu(s):  25.3%us" | grep -E "Cpu.*[0-7][0-9]\.[0-9]%"
# If no output, pattern is wrong; adjust it
```

---

## Integration with Phase 2 (Cloud Remediation)

In Phase 2, you'll add webhook callbacks where cloud's Claude LLM:
1. Receives the remediation result
2. Analyzes root cause
3. Suggests follow-up actions
4. POSTs back to `/webhooks/rca-result`
5. Client logs LLM insights alongside local remediation

This creates a hybrid system:
- **Client** = Fast, deterministic, automatic
- **Cloud** = Deep, intelligent, human-informed

---

## Summary

| Aspect | Client Remediation | Cloud RCA |
|--------|-------------------|-----------|
| **Speed** | < 1 second | 5-30 seconds |
| **Automation** | Fully automatic | Suggestions → manual |
| **Availability** | Offline-capable | Needs cloud |
| **Scope** | Common issues | Any issue |
| **Goal** | Quick fix | Root cause |
| **Example** | "Kill rogue process" | "Process consuming CPU due to memory leak in X" |

Together, they provide **fast first aid + intelligent diagnosis**.

---

## Support

For issues or questions:
1. Check logs: `tail -f opsbot_client.log | grep Remediation`
2. Validate playbook: `python -m json.tool remediation_playbooks.json`
3. Test manually: Run command directly on shell
4. Review ARCHITECTURE.md for system design

