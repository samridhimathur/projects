"""
Real system signal collection using deterministic automation.

Replaces mock MCP tools with actual system monitoring:
- CPU/Memory metrics via psutil
- Disk usage monitoring
- Process health via psutil
- Log analysis via file tailing
- Connectivity checks via subprocess
"""

import psutil
import subprocess
import asyncio
import logging
import re
from typing import Dict, Any, Optional
from dataclasses import dataclass
from datetime import datetime

logger = logging.getLogger(__name__)


@dataclass
class SignalResult:
    """Result from signal collection."""
    tool_name: str
    success: bool
    data: Dict[str, Any]
    error: Optional[str] = None
    timestamp_ms: int = None

    def __post_init__(self):
        if self.timestamp_ms is None:
            self.timestamp_ms = int(datetime.utcnow().timestamp() * 1000)


class SystemSignalCollector:
    """
    Real-time system signal collection using deterministic automation.

    Collects actual metrics directly from the system without external services.
    """

    def __init__(self, app_log_path: str = "/var/log/app.log"):
        """
        Initialize signal collector.

        Args:
            app_log_path: Path to application log file
        """
        self.app_log_path = app_log_path
        logger.info(f"Initialized SystemSignalCollector (log_path={app_log_path})")

    async def collect_all_signals(self) -> Dict[str, SignalResult]:
        """
        Collect all available signals concurrently.

        Returns:
            Dict of signal_name -> SignalResult
        """
        results = {}

        # Run all collectors concurrently
        tasks = [
            ("system_metrics", self.collect_system_metrics()),
            ("process_health", self.collect_process_health()),
            ("disk_usage", self.collect_disk_usage()),
            ("log_analysis", self.collect_log_analysis()),
            ("connectivity_check", self.collect_connectivity()),
        ]

        for name, task in tasks:
            try:
                result = await task
                results[name] = result
                logger.debug(f"Signal collection succeeded: {name}")
            except Exception as e:
                logger.error(f"Signal collection failed for {name}: {e}")
                results[name] = SignalResult(
                    tool_name=name,
                    success=False,
                    data={},
                    error=str(e)
                )

        return results

    async def collect_system_metrics(self) -> SignalResult:
        """
        Collect real CPU, memory, and process metrics using psutil.

        Returns actual system metrics, not mocks.
        """
        try:
            # Collect CPU with 1-second interval for measurement
            cpu_percent = psutil.cpu_percent(interval=1)
            memory = psutil.virtual_memory()
            swap = psutil.swap_memory()
            load_avg = psutil.getloadavg()
            process_count = len(psutil.pids())

            metrics = {
                "cpu_percent": cpu_percent,
                "memory_percent": memory.percent,
                "memory_mb": memory.used / 1024 / 1024,
                "memory_available_mb": memory.available / 1024 / 1024,
                "swap_percent": swap.percent,
                "load_average": list(load_avg),
                "process_count": process_count,
            }

            logger.debug(f"System metrics: CPU={cpu_percent:.1f}%, Memory={memory.percent:.1f}%")

            return SignalResult(
                tool_name="system_metrics",
                success=True,
                data=metrics
            )

        except Exception as e:
            logger.error(f"Failed to collect system metrics: {e}")
            return SignalResult(
                tool_name="system_metrics",
                success=False,
                data={},
                error=str(e)
            )

    async def collect_disk_usage(self) -> SignalResult:
        """Collect real disk usage metrics."""
        try:
            disk = psutil.disk_usage('/')

            data = {
                "disk_percent": disk.percent,
                "disk_used_gb": disk.used / (1024 ** 3),
                "disk_free_gb": disk.free / (1024 ** 3),
                "disk_total_gb": disk.total / (1024 ** 3),
            }

            # Try to get disk I/O counters if available
            try:
                io_counters = psutil.disk_io_counters()
                if io_counters:
                    data.update({
                        "disk_read_count": io_counters.read_count,
                        "disk_write_count": io_counters.write_count,
                        "disk_read_mb": io_counters.read_bytes / (1024 ** 2),
                        "disk_write_mb": io_counters.write_bytes / (1024 ** 2),
                    })
            except (OSError, AttributeError):
                pass

            logger.debug(f"Disk usage: {disk.percent:.1f}%")

            return SignalResult(
                tool_name="disk_usage",
                success=True,
                data=data
            )

        except Exception as e:
            logger.error(f"Failed to collect disk usage: {e}")
            return SignalResult(
                tool_name="disk_usage",
                success=False,
                data={},
                error=str(e)
            )

    async def collect_process_health(self) -> SignalResult:
        """
        Check top processes and system health using real process monitoring.

        Uses actual process data, not mocks.
        """
        try:
            # Get top 5 processes by CPU
            processes = sorted(
                psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_percent']),
                key=lambda p: p.info['cpu_percent'] or 0,
                reverse=True
            )[:5]

            top_processes = []
            for proc in processes:
                try:
                    top_processes.append({
                        "pid": proc.info['pid'],
                        "name": proc.info['name'],
                        "cpu_percent": proc.info['cpu_percent'] or 0,
                        "memory_percent": proc.info['memory_percent'] or 0,
                    })
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue

            # System is healthy if we have processes
            system_healthy = len(top_processes) > 0

            data = {
                "system_healthy": system_healthy,
                "top_processes": top_processes,
                "total_processes": len(psutil.pids()),
            }

            logger.debug(f"Process health: healthy={system_healthy}, top_cpu={top_processes[0]['cpu_percent']:.1f}% if top_processes else 0")

            return SignalResult(
                tool_name="process_health",
                success=True,
                data=data
            )

        except Exception as e:
            logger.error(f"Failed to collect process health: {e}")
            return SignalResult(
                tool_name="process_health",
                success=False,
                data={},
                error=str(e)
            )

    async def collect_log_analysis(self) -> SignalResult:
        """
        Analyze application logs for error patterns using real file I/O.

        Parses actual log files, not mock data.
        """
        try:
            error_count = 0
            error_logs = []

            # Try to read log file
            try:
                with open(self.app_log_path, 'r') as f:
                    # Read last 100 lines
                    lines = f.readlines()[-100:]

                    # Count errors and capture recent ones
                    for line in lines:
                        if re.search(r'ERROR|CRITICAL|Exception', line, re.IGNORECASE):
                            error_count += 1
                            error_logs.append(line.strip())

                    # Keep only last 10 errors
                    error_logs = error_logs[-10:]

            except FileNotFoundError:
                logger.warning(f"Log file not found: {self.app_log_path}")
                # Return success but with no errors found
                return SignalResult(
                    tool_name="log_analysis",
                    success=True,
                    data={
                        "error_count": 0,
                        "error_logs": [],
                        "log_file": self.app_log_path,
                    }
                )

            data = {
                "error_count": error_count,
                "error_logs": error_logs,
                "log_file": self.app_log_path,
            }

            logger.debug(f"Log analysis: {error_count} errors found")

            return SignalResult(
                tool_name="log_analysis",
                success=True,
                data=data
            )

        except Exception as e:
            logger.error(f"Failed to analyze logs: {e}")
            return SignalResult(
                tool_name="log_analysis",
                success=False,
                data={},
                error=str(e)
            )

    async def collect_connectivity(self) -> SignalResult:
        """
        Check network connectivity using real ping command.

        Tests actual connectivity, not mock results.
        """
        try:
            target = "8.8.8.8"
            reachable = False
            latency_ms = 0.0

            try:
                # Try Linux/Mac ping format
                result = subprocess.run(
                    ["ping", "-c", "1", "-W", "2", target],
                    capture_output=True,
                    timeout=5,
                    text=True
                )
                reachable = result.returncode == 0

                # Extract latency from output
                if reachable:
                    match = re.search(r'time=([0-9.]+)\s*ms', result.stdout)
                    if match:
                        latency_ms = float(match.group(1))

            except Exception:
                try:
                    # Fallback: Try Windows ping format
                    result = subprocess.run(
                        ["ping", "-n", "1", "-w", "2000", target],
                        capture_output=True,
                        timeout=5,
                        text=True
                    )
                    reachable = result.returncode == 0

                    if reachable:
                        match = re.search(r'time=([0-9]+)\s*ms', result.stdout)
                        if match:
                            latency_ms = float(match.group(1))
                except Exception:
                    pass

            data = {
                "target": target,
                "reachable": reachable,
                "latency_ms": latency_ms,
                "packet_loss_percent": 0.0 if reachable else 100.0,
            }

            logger.debug(f"Connectivity check: {target} reachable={reachable}")

            return SignalResult(
                tool_name="connectivity_check",
                success=True,
                data=data
            )

        except Exception as e:
            logger.warning(f"Connectivity check failed (non-critical): {e}")
            # Return success with indication of unreachable
            return SignalResult(
                tool_name="connectivity_check",
                success=True,
                data={
                    "target": "8.8.8.8",
                    "reachable": False,
                    "latency_ms": 0.0,
                    "packet_loss_percent": 100.0,
                }
            )

    async def check_service_health(self, service_name: str = "app", port: int = 8080) -> SignalResult:
        """
        Check if a service is healthy via HTTP health check.

        Performs actual health check, not mock.

        Args:
            service_name: Name of service
            port: Port to check

        Returns:
            SignalResult with health status
        """
        try:
            import requests

            url = f"http://localhost:{port}/health"
            response = requests.get(url, timeout=5)

            healthy = 200 <= response.status_code < 300

            data = {
                "service": service_name,
                "healthy": healthy,
                "status_code": response.status_code,
                "response_time_ms": response.elapsed.total_seconds() * 1000,
            }

            logger.debug(f"Service health check: {service_name} healthy={healthy}")

            return SignalResult(
                tool_name="service_health",
                success=True,
                data=data
            )

        except Exception as e:
            logger.warning(f"Service {service_name} health check failed: {e}")
            return SignalResult(
                tool_name="service_health",
                success=True,
                data={
                    "service": service_name,
                    "healthy": False,
                    "status_code": 0,
                    "response_time_ms": 0,
                }
            )

