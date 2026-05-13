# OpsBot Client

Lightweight anomaly detection and alert event producer for OpsBot.

**Architecture:**
```
MCP Tools                Rule Engine              Cloud API
(logs, metrics)    →    (thresholds)         →   (ingestion)
                        ↓
                    Local Buffer
                   (SQLite, offline)
```

## Features

✅ **Lightweight client** - No LLM, no heavy dependencies
✅ **Deterministic rules** - Predictable anomaly detection
✅ **Offline resilience** - Local SQLite buffer with retry
✅ **MCP integration** - Collect signals via Model Context Protocol tools
✅ **Structured events** - JSON AlertEvents matching Java backend
✅ **Exponential backoff** - Smart retries with configurable backoff
✅ **Configurable rules** - JSON-based rule configuration with hot-reload

## Quick Start

### 1. Install Dependencies

```bash
pip install -r requirements.txt
```

### 2. Configure Environment

```bash
cp .env.example .env
# Edit .env with your settings
```

Key configuration:
- `OPSBOT_CLIENT_SOURCE`: Client identifier (e.g., "prod-server-01")
- `OPSBOT_CLOUD_URL`: Cloud API base URL (default: http://localhost:8080)
- `OPSBOT_CHECK_INTERVAL_SECONDS`: How often to check for anomalies (default: 30)

### 3. Run Client

```bash
python -m opsbot_client.main
```

Expected output:
```
=== OpsBot Client v0.1.0 ===
Configuration: source=dev-machine-01, env=dev
Cloud API: http://localhost:8080/api/ingest
INFO: Initialized OpsBotClient
INFO: Starting OpsBotClient monitoring loop
...
```

## Configuration

### Rules (rules.json)

Rules are defined in `rules.json` and determine what triggers an alert.

Example rule:
```json
{
  "rule_name": "high_cpu_85_percent",
  "alert_type": "high_cpu",
  "severity": "HIGH",
  "metric_name": "cpu_percent",
  "operator": "greater_than",
  "threshold": 85.0,
  "cooldown_seconds": 300
}
```

**Operators:**
- `greater_than`: value > threshold
- `less_than`: value < threshold
- `equals`: value == threshold
- `contains`: pattern appears in value (regex)
- `regex`: regex match

**Cooldown:** Prevents duplicate alerts within N seconds.

### Environment Variables

```bash
# Client identity
OPSBOT_CLIENT_SOURCE=prod-server-01          # Required
OPSBOT_ENVIRONMENT=production                # dev, staging, production

# Cloud API
OPSBOT_CLOUD_URL=http://opscloud.example.com
OPSBOT_INGESTION_PATH=/api/ingest

# Retry policy
OPSBOT_RETRY_MAX_ATTEMPTS=3
OPSBOT_RETRY_BACKOFF_SECONDS=2               # Exponential: 1, 2, 4, 8...

# Local persistence
OPSBOT_BUFFER_DB=./opsbot_buffer.db

# Rules
OPSBOT_RULES_CONFIG=./rules.json

# Monitoring
OPSBOT_CHECK_INTERVAL_SECONDS=30

# Logging
OPSBOT_LOG_LEVEL=INFO                        # DEBUG, INFO, WARNING, ERROR
```

## Workflow

### Signal Collection (MCP Tools)

The client collects signals from MCP tools:

- **get_metrics** - CPU, memory, disk, network
- **service_health** - Service status, uptime, error rate
- **log_tail** - Recent error logs (for context)
- **check_connectivity** - Network connectivity tests

Example signals:
```python
{
    "cpu_percent": 92.5,
    "memory_percent": 88.3,
    "disk_percent": 45.0,
    "service_healthy": True,
    "error_rate": 0.02,
    "response_time_p99_ms": 250
}
```

### Anomaly Detection (Rule Engine)

For each rule, the rule engine:
1. Gets the metric value from signals
2. Applies the operator and threshold
3. Checks cooldown to suppress duplicates
4. Creates AlertEvent on match

Example: Rule `high_cpu` triggers if `cpu_percent > 85.0`

### Event Sending (Cloud API)

AlertEvent is sent to cloud ingestion API:

```bash
POST http://cloud-api/api/ingest
Content-Type: application/json

{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "source": "prod-server-01",
  "service": "api-gateway",
  "alert_type": "high_cpu",
  "severity": "HIGH",
  "detectedAtMs": 1714915522000,
  "signals": {"cpu_percent": 92.5},
  "contextLogs": ["[ERROR] CPU spike detected"],
  "fingerprint": "abc123def456",
  "created_at": "2026-05-02T10:15:22Z"
}
```

Response:
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "accepted"
}
```

### Failure Handling (Local Buffer)

If the send fails:
1. Event is stored in SQLite buffer
2. Retries are attempted with exponential backoff
3. On final failure, event remains in buffer
4. Buffer daemon periodically replays pending events

Buffer queries:
```python
buffer = EventBuffer()

# Add failed event to buffer
buffer.add_event(alert)

# Get pending events for retry
pending = buffer.get_pending_events(limit=100)

# Remove on successful send
buffer.remove_event(event_id)

# Check stats
stats = buffer.get_stats()
# {"total_buffered": 5, "total_retries": 12, "avg_retries": 2.4}
```

## Metrics & Observability

The client emits structured JSON logs for each stage:

```json
{
  "timestamp": "2026-05-02T10:15:22.123456Z",
  "level": "INFO",
  "name": "opsbot-client",
  "message": "Alert event 550e8400-e29b-41d4-a716-446655440000 accepted for processing",
  "source": "prod-server-01",
  "environment": "dev"
}
```

Key log events:
- `Collecting signals from MCP tools...`
- `Running anomaly detection...`
- `Detected X anomalies`
- `Event X sent successfully`
- `Event X buffered (will retry)`
- `Replaying X buffered events`

## Architecture Decisions

### Why Deterministic Rules (No ML)?
- **Predictable** - Threshold breaches are deterministic
- **Fast** - No inference latency
- **Explainable** - Easy to debug why alert fired
- **Lightweight** - No ML libraries, minimal deps

### Why Local Buffer?
- **Offline resilience** - Client continues collecting when cloud is down
- **Data loss prevention** - SQLite persists events to disk
- **Automatic retry** - Failed events replayed when connectivity returns
- **Simple** - SQLite is embedded, no external storage needed

### Why Structured Events?
- **Schema versioning** - Both client and cloud agree on event format
- **Backend simplicity** - Cloud side doesn't parse unstructured logs
- **Deduplication** - Server fingerprints prevent duplicate processing
- **Context preservation** - Relevant logs included with each alert

## Testing

Run unit tests:

```bash
pytest tests/ -v
```

Example test:

```python
async def test_high_cpu_rule():
    engine = RuleEngine()
    
    result = engine.detect_anomalies(
        source="test-server",
        service="api",
        tool_results={
            "get_metrics": MCPToolResult(
                tool_name="get_metrics",
                success=True,
                data={"cpu_percent": 92.5}
            )
        }
    )
    
    assert len(result.anomalies_detected) == 1
    assert result.anomalies_detected[0].alert_type == "high_cpu"
```

## Deployment

### Docker

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY . .
ENV OPSBOT_CLIENT_SOURCE=docker-client
ENV OPSBOT_CLOUD_URL=http://opsbot-cloud:8080
CMD ["python", "-m", "opsbot_client.main"]
```

```bash
docker build -t opsbot-client .
docker run -e OPSBOT_CLIENT_SOURCE=prod-01 opsbot-client
```

### Kubernetes

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: opsbot-client-config
data:
  rules.json: |
    { ... rules ... }
  .env: |
    OPSBOT_CLIENT_SOURCE=k8s-pod
    OPSBOT_CLOUD_URL=http://opsbot-cloud:8080

---
apiVersion: v1
kind: Pod
metadata:
  name: opsbot-client
spec:
  containers:
  - name: client
    image: opsbot-client:latest
    volumeMounts:
    - name: config
      mountPath: /app/config
  volumes:
  - name: config
    configMap:
      name: opsbot-client-config
```

## Debugging

### Enable debug logging

```bash
OPSBOT_LOG_LEVEL=DEBUG python -m opsbot_client.main
```

### Inspect buffer

```python
from buffer import EventBuffer

buffer = EventBuffer()
stats = buffer.get_stats()
print(f"Buffered events: {stats}")

pending = buffer.get_pending_events()
for event in pending:
    print(f"Event {event.event_id}: {event.alert_type}")
```

### Test cloud connectivity

```bash
curl -X POST http://localhost:8080/api/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "test-123",
    "source": "test",
    "service": "test",
    "alertType": "test",
    "severity": "LOW",
    "detectedAtMs": 1234567890000,
    "signals": {},
    "fingerprint": "test"
  }'
```

## License

MIT

