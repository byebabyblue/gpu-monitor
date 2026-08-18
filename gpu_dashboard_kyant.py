#!/usr/bin/env python3
"""
╔══════════════════════════════════════════════════════════════════════╗
║                    SSH GPU Monitor Dashboard                        ║
║          Real-time multi-server GPU / CPU / RAM / Disk monitor      ║
╚══════════════════════════════════════════════════════════════════════╝

Requirements:
    pip install PyQt5 paramiko pyqtgraph

Usage:
    python gpu_dashboard.py --demo            # demo mode with simulated data
    python gpu_dashboard.py                   # uses config.json in same dir
    python gpu_dashboard.py -c servers.json   # custom config path

Config (config.json):
    {
      "servers": [
        {
          "name": "Server Name",
          "host": "192.168.1.100",
          "port": 22,
          "user": "username",
          "key":  "~/.ssh/id_rsa",
          "password": "xxx",
          "passphrase": "xxx",
          "disk_path": "/root/autodl-tmp"
        }
      ],
      "refresh_interval": 3
    }
"""

import sys
import json
import copy
import os
import shutil
import time
import math
import random
import argparse
import re
import shlex
from datetime import datetime
from pathlib import Path
from threading import Lock, RLock
from urllib.parse import unquote, urlsplit

try:
    import paramiko
    HAS_PARAMIKO = True
except ImportError:
    HAS_PARAMIKO = False
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QFrame, QScrollArea, QTableWidget,
    QTableWidgetItem, QHeaderView,
    QPushButton, QComboBox, QInputDialog,
)
from PyQt5.QtCore import Qt, QTimer, QThread, pyqtSignal, QMimeData
from PyQt5.QtGui import QFont, QPainter, QColor, QPen, QDrag
import http.server
import threading

# pyqtgraph is optional – dashboard still works without the history chart
try:
    import pyqtgraph as pg
    HAS_PYQTGRAPH = True
except ImportError:
    HAS_PYQTGRAPH = False

# ═══════════════════════════════════════════════════════════════════════
#  Theme
# ═══════════════════════════════════════════════════════════════════════

class C:
    BG             = "#0f1117"
    SIDEBAR        = "#161822"
    CARD           = "#1a1d2e"
    CARD_HOVER     = "#22253a"
    CARD_SELECTED  = "#252940"
    TEXT           = "#e2e8f0"
    DIM            = "#8892b0"
    MUTED          = "#4a5568"
    BORDER         = "#2d3148"
    ACCENT         = "#6366f1"
    ACCENT2        = "#8b5cf6"
    GREEN          = "#22c55e"
    YELLOW         = "#f59e0b"
    RED            = "#ef4444"
    BLUE           = "#3b82f6"
    TEAL           = "#14b8a6"
    CHART_BG       = "#13151f"

    COLORS = [
        "#6366f1", "#22c55e", "#f59e0b", "#ef4444",
        "#3b82f6", "#ec4899", "#14b8a6", "#f97316",
    ]


class RefreshSettings:
    gpu_active_interval = 3
    gpu_idle_interval = 60
    disk_interval = 30
    process_interval = 6
    idle_threshold = 600  # 10 min before slow polling
    _config_path = None

    @staticmethod
    def _bounded_number(value, default, minimum, maximum):
        try:
            value = float(value)
        except (TypeError, ValueError):
            return default
        return max(minimum, min(maximum, value))

    @classmethod
    def load(cls, cfg: dict):
        s = cfg.get("settings", {})
        legacy_interval = cfg.get("refresh_interval", 3)
        cls.gpu_active_interval = cls._bounded_number(
            s.get("gpu_active_interval", legacy_interval), 3, 1, 300
        )
        cls.gpu_idle_interval = cls._bounded_number(
            s.get("gpu_idle_interval", 60), 60, 5, 3600
        )
        cls.disk_interval = cls._bounded_number(
            s.get("disk_interval", 30), 30, 5, 21600
        )
        cls.process_interval = cls._bounded_number(
            s.get("process_interval", 6), 6, 2, 300
        )
        cls.idle_threshold = cls._bounded_number(
            s.get("idle_threshold", 600), 600, 30, 86400
        )

    @classmethod
    def save(cls, config_path):
        if config_path is None:
            return
        try:
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except (OSError, json.JSONDecodeError):
            cfg = {"servers": [], "refresh_interval": 3}
        settings = cfg.get("settings", {})
        if not isinstance(settings, dict):
            settings = {}
        settings.update({
            "gpu_active_interval": cls.gpu_active_interval,
            "gpu_idle_interval":   cls.gpu_idle_interval,
            "disk_interval":       cls.disk_interval,
            "process_interval":    cls.process_interval,
            "idle_threshold":      cls.idle_threshold,
        })
        cfg["settings"] = settings
        try:
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(cfg, f, indent=2, ensure_ascii=False)
        except OSError:
            pass

    @classmethod
    def _auto_save(cls):
        cls.save(cls._config_path)


def color_for_pct(v):
    if v >= 90:
        return C.RED
    if v >= 70:
        return C.YELLOW
    return C.GREEN


# ═══════════════════════════════════════════════════════════════════════
#  Data container
# ═══════════════════════════════════════════════════════════════════════

class ServerInfo:
    """Holds the latest snapshot of one server's metrics."""

    def __init__(self, name):
        self.name      = name
        self.status    = "connecting"
        self.connected = False
        self.error     = ""

        self.cpu_pct = 0.0
        self.cpu_cores = 0
        self.cpu_model = ""

        self.gpus: list[dict] = []
        self.gpu_model = ""

        self.mem_used = self.mem_total = self.mem_pct = 0.0

        self.disk_used = self.disk_total = self.disk_pct = 0.0
        self.disks: list[dict] = []  # individual filesystems, e.g. /data1, /data2

        self.running_tasks: list[dict] = []
        self.queued_tasks: list[dict]  = []

        self.gpu_history: list[list[dict]] = []   # per-GPU rolling history
        self.last_update = None
        self.gpu_events: list[dict] = []          # GPU state change events
        self._prev_gpu_active: dict[int, bool] = {}   # gpu_idx -> was active last poll
        self._gpu_freed_at: dict[int, float] = {}     # gpu_idx -> when it became idle


# ═══════════════════════════════════════════════════════════════════════
#  SSH helpers
# ═══════════════════════════════════════════════════════════════════════

class SSHConnection:
    """Thin wrapper around paramiko.SSHClient with keep-alive & reconnect."""

    def __init__(self, host, port, user,
                 key_file=None, password=None, passphrase=None):
        self.host       = host
        self.port       = port
        self.user       = user
        self.key_file   = key_file
        self.password   = password
        self.passphrase = passphrase
        self._client = None
        self._lock = Lock()

    # ── connect / disconnect ──────────────────────────────────────────

    def connect(self):
        if not HAS_PARAMIKO:
            raise RuntimeError("paramiko is not installed; run: pip install paramiko")

        self.disconnect()
        client = paramiko.SSHClient()
        client.load_system_host_keys()
        client.set_missing_host_key_policy(paramiko.AutoAddPolicy())

        kwargs = dict(
            hostname=self.host,
            port=self.port,
            username=self.user,
            timeout=10,
            banner_timeout=10,
            auth_timeout=10,
        )

        key_was_added = False
        if self.key_file:
            pkey_path = Path(self.key_file).expanduser()
            if pkey_path.exists():
                kwargs["key_filename"] = str(pkey_path)
                key_was_added = True
                if self.passphrase:
                    kwargs["passphrase"] = self.passphrase
        if self.password:
            kwargs["password"] = self.password

        # Avoid a slow scan of unrelated local keys when explicit credentials
        # are supplied. Agent authentication remains available otherwise.
        if key_was_added or self.password:
            kwargs["look_for_keys"] = False

        client.connect(**kwargs)
        t = client.get_transport()
        if t:
            t.set_keepalive(30)
        self._client = client

    def disconnect(self):
        with self._lock:
            if self._client:
                try:
                    self._client.close()
                except Exception:
                    pass
                self._client = None

    def connected(self) -> bool:
        with self._lock:
            client = self._client
            transport = client.get_transport() if client else None
            return bool(transport and transport.is_active())

    # ── exec ──────────────────────────────────────────────────────────

    def exec(self, cmd: str, timeout: int = 15, check: bool = False) -> str:
        with self._lock:
            client = self._client
            if not client:
                raise ConnectionError("SSH not connected")
        try:
            _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
            raw_out = stdout.read()
            raw_err = stderr.read()
            status = stdout.channel.recv_exit_status()
        except Exception as exc:
            self.disconnect()
            raise ConnectionError(f"SSH command failed: {exc}") from exc

        out = raw_out.decode("utf-8", errors="replace")
        err = raw_err.decode("utf-8", errors="replace").strip()
        if check and status != 0:
            raise RuntimeError(err or f"remote command exited with status {status}")
        return out

    # ── reconnect with back-off ───────────────────────────────────────

    def reconnect(self, max_retries: int = 5) -> bool:
        for i in range(max_retries):
            self.disconnect()
            try:
                self.connect()
                return True
            except Exception:
                time.sleep(min(2 ** i, 30))
        return False


# ═══════════════════════════════════════════════════════════════════════
#  Remote data collection
# ═══════════════════════════════════════════════════════════════════════

class DataCollector:
    """Collect and parse remote metrics with rate-limited expensive probes."""

    _CPU_MEM_CMD = r'''python3 - <<'PY_REMOTE'
import json, os, time

def cpu_sample():
    with open('/proc/stat', 'r', encoding='utf-8') as f:
        values = [int(x) for x in f.readline().split()[1:]]
    total = sum(values)
    idle = values[3] + (values[4] if len(values) > 4 else 0)
    return total, idle

t1, i1 = cpu_sample()
time.sleep(0.15)
t2, i2 = cpu_sample()
dt = max(1, t2 - t1)
cpu_pct = max(0.0, min(100.0, ((dt - (i2 - i1)) / dt) * 100.0))

mem = {}
with open('/proc/meminfo', 'r', encoding='utf-8') as f:
    for line in f:
        key, value = line.split(':', 1)
        mem[key] = int(value.strip().split()[0])
mem_total = mem.get('MemTotal', 0) / 1024.0
mem_available = mem.get('MemAvailable', mem.get('MemFree', 0)) / 1024.0
mem_used = max(0.0, mem_total - mem_available)

model = ''
try:
    with open('/proc/cpuinfo', 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.lower().startswith('model name'):
                model = line.split(':', 1)[1].strip()
                break
except OSError:
    pass

print(json.dumps({
    'cpu_pct': cpu_pct,
    'cpu_cores': os.cpu_count() or 0,
    'cpu_model': model,
    'mem_total': mem_total,
    'mem_used': mem_used,
}))
PY_REMOTE'''

    _CPU_MEM_FALLBACK_CMD = r'''LC_ALL=C awk '
function sample_cpu(    line, fields, count, i) {
    if ((getline line < "/proc/stat") <= 0) exit 2
    close("/proc/stat")
    count = split(line, fields, /[[:space:]]+/)
    sample_total = 0
    for (i = 2; i <= count; i++) sample_total += fields[i]
    sample_idle = fields[5] + fields[6]
}
BEGIN {
    sample_cpu(); total1 = sample_total; idle1 = sample_idle
    system("sleep 0.15")
    sample_cpu(); total2 = sample_total; idle2 = sample_idle
    delta_total = total2 - total1
    cpu_pct = delta_total > 0 ? 100 * (delta_total - (idle2 - idle1)) / delta_total : 0

    while ((getline line < "/proc/meminfo") > 0) {
        split(line, item, /[[:space:]:]+/)
        if (item[1] == "MemTotal") mem_total = item[2] / 1024
        else if (item[1] == "MemAvailable") mem_available = item[2] / 1024
        else if (item[1] == "MemFree") mem_free = item[2] / 1024
    }
    close("/proc/meminfo")
    if (!mem_available) mem_available = mem_free
    mem_used = mem_total - mem_available
    if (mem_used < 0) mem_used = 0

    while ((getline line < "/proc/cpuinfo") > 0) {
        if (line ~ /^processor[[:space:]]*:/) cpu_cores++
        if (!cpu_model && line ~ /^model name[[:space:]]*:/) {
            sub(/^[^:]*:[[:space:]]*/, "", line)
            cpu_model = line
        }
    }
    close("/proc/cpuinfo")
    gsub(/\\/, "\\\\", cpu_model)
    gsub(/\"/, "\\\"", cpu_model)
    printf "{\"cpu_pct\":%.6f,\"cpu_cores\":%d,\"cpu_model\":\"%s\",\"mem_total\":%.6f,\"mem_used\":%.6f}\n", cpu_pct, cpu_cores, cpu_model, mem_total, mem_used
}' '''

    _GPU_CMD = (
        "(nvidia-smi --query-gpu=index,name,utilization.gpu,memory.used,"
        "memory.total,temperature.gpu,power.draw,power.limit "
        "--format=csv,noheader,nounits 2>/dev/null || true); "
        "printf '\\n__GPU_PIDS__\\n'; "
        "(nvidia-smi --query-compute-apps=pid "
        "--format=csv,noheader,nounits 2>/dev/null || true)"
    )

    _PS_CMD = (
        "ps -eo user=,pid=,pcpu=,pmem=,etime=,state=,args= "
        "--sort=-pcpu 2>/dev/null | head -n 80"
    )

    def __init__(self, ssh: SSHConnection, disk_path: str = "."):
        self.ssh = ssh
        self.disk_path = str(disk_path or ".").strip() or "."
        self._last_disk_poll = 0.0
        self._last_process_poll = 0.0
        self._gpu_pids: set[str] = set()

    def collect_all(self, info: ServerInfo) -> ServerInfo:
        """Update a snapshot. Connection failures propagate to the monitor thread."""
        self._cpu_mem(info)
        self._gpu(info)

        now = time.monotonic()
        if now - self._last_disk_poll >= RefreshSettings.disk_interval:
            self._disk(info)
            self._last_disk_poll = now

        if now - self._last_process_poll >= RefreshSettings.process_interval:
            running, queued = self._processes()
            info.running_tasks = running
            info.queued_tasks = queued
            self._last_process_poll = now

        info.status = "connected"
        info.connected = True
        info.error = ""
        info.last_update = datetime.now()
        return info

    def _cpu_mem(self, info: ServerInfo):
        try:
            out = self.ssh.exec(self._CPU_MEM_CMD, timeout=8, check=True)
        except RuntimeError:
            # Minimal AutoDL/SeetaCloud images may provide nvidia-smi and
            # standard procfs tools but no Python interpreter at all.
            out = self.ssh.exec(self._CPU_MEM_FALLBACK_CMD, timeout=8, check=True)
        try:
            data = json.loads(out.strip().splitlines()[-1])
        except (IndexError, json.JSONDecodeError) as exc:
            raise RuntimeError("unable to parse remote CPU/memory data") from exc

        info.cpu_pct = _clamp(_safe_float(data.get("cpu_pct")), 0, 100)
        info.cpu_cores = max(0, int(data.get("cpu_cores") or 0))
        info.cpu_model = str(data.get("cpu_model") or info.cpu_model)
        info.mem_total = max(0.0, _safe_float(data.get("mem_total")))
        info.mem_used = _clamp(_safe_float(data.get("mem_used")), 0, info.mem_total)
        info.mem_pct = (
            info.mem_used / info.mem_total * 100 if info.mem_total else 0.0
        )

    def _gpu(self, info: ServerInfo):
        out = self.ssh.exec(self._GPU_CMD, timeout=10)
        gpu_part, _, pid_part = out.partition("__GPU_PIDS__")
        gpus = []
        for line in gpu_part.strip().splitlines():
            if not line.strip() or "[ERR" in line:
                continue
            p = [x.strip() for x in line.split(",")]
            if len(p) < 6:
                continue
            try:
                idx = int(p[0])
            except ValueError:
                continue
            mem_used = _safe_float(p[3])
            mem_total = _safe_float(p[4])
            gpus.append({
                "idx": idx,
                "name": p[1],
                "util": _clamp(_safe_float(p[2]), 0, 100),
                "mem_used": max(0.0, mem_used),
                "mem_total": max(0.0, mem_total),
                "mem_pct": mem_used / mem_total * 100 if mem_total else 0.0,
                "temp": max(0.0, _safe_float(p[5])),
                "power": max(0.0, _safe_float(p[6])) if len(p) > 6 else 0.0,
                "power_lim": max(0.0, _safe_float(p[7])) if len(p) > 7 else 0.0,
            })

        info.gpus = gpus
        info.gpu_model = gpus[0]["name"] if gpus else ""
        self._gpu_pids = {
            line.split(",", 1)[0].strip()
            for line in pid_part.splitlines()
            if line.strip() and line.split(",", 1)[0].strip().isdigit()
        }

        while len(info.gpu_history) < len(gpus):
            info.gpu_history.append([])
        info.gpu_history = info.gpu_history[:len(gpus)]

        timestamp = time.time()
        for i, gpu in enumerate(gpus):
            self._append_history(info.gpu_history[i], timestamp, gpu["util"])

    @staticmethod
    def _append_history(history: list[dict], timestamp: float, value: float):
        """Keep recent samples at full resolution and compact older data by minute."""
        history.append({"t": timestamp, "v": value})
        cutoff_24h = timestamp - 24 * 3600
        while history and history[0]["t"] < cutoff_24h:
            history.pop(0)

        if len(history) <= 3600:
            return

        recent_cutoff = timestamp - 30 * 60
        buckets = {}
        recent = []
        for sample in history:
            if sample["t"] >= recent_cutoff:
                recent.append(sample)
                continue
            bucket = int(sample["t"] // 60)
            total, count = buckets.get(bucket, (0.0, 0))
            buckets[bucket] = (total + sample["v"], count + 1)

        compacted = [
            {"t": bucket * 60 + 30, "v": total / count}
            for bucket, (total, count) in sorted(buckets.items())
        ]
        history[:] = compacted + recent[-3000:]

    def _disk(self, info: ServerInfo):
        """Collect every useful local data mount in one SSH round-trip.

        Explicit ``disk_path`` is checked first.  The collector also discovers
        /data*, /root/data* and only falls back to the login directory
        when no data mount exists.  POSIX ``df -P -k`` is used because it is
        considerably more portable than GNU-only ``-B1``.
        """
        configured = self.disk_path
        if isinstance(configured, (list, tuple)):
            configured_paths = [str(x).strip() for x in configured if str(x).strip()]
        else:
            configured_paths = [x.strip() for x in str(configured or ".").split(",") if x.strip()]
        if not configured_paths:
            configured_paths = ["."]

        literal_paths = " ".join(shlex.quote(x) for x in configured_paths)
        discovery_paths = " /data /data[0-9]* /root/data /root/data[0-9]*"
        command = (
            "seen=''; "
            f"for p in {literal_paths}{discovery_paths}; do "
            "[ -e \"$p\" ] || continue; "
            "case \" $seen \" in *\" $p \"*) continue;; esac; seen=\"$seen $p\"; "
            "line=$(LC_ALL=C df -P -k \"$p\" 2>/dev/null | tail -n 1) || continue; "
            "[ -n \"$line\" ] && printf '%s\\t%s\\n' \"$p\" \"$line\"; "
            "done"
        )
        out = self.ssh.exec(command, timeout=10)
        disks = []
        for line in out.splitlines():
            if "\t" not in line:
                continue
            label, df_line = line.split("\t", 1)
            parts = df_line.split()
            if len(parts) < 6 or not parts[4].endswith("%"):
                continue
            total_gib = max(0.0, _safe_float(parts[1]) / (1024 ** 2))
            used_gib = max(0.0, _safe_float(parts[2]) / (1024 ** 2))
            disks.append({
                "path": label,
                "filesystem": parts[0],
                "mount": parts[5],
                "total": total_gib,
                "used": used_gib,
                "percent": _clamp(_safe_float(parts[4].rstrip("%")), 0, 100),
            })

        # If /data* exists, omit '.' when it merely points at the system disk.
        data_disks = [
            d for d in disks
            if d["path"] == "/data"
            or d["path"].startswith("/data")
            or d["path"] == "/root/data"
            or d["path"].startswith("/root/data")
        ]
        if data_disks:
            explicit = set(configured_paths) - {"."}
            disks = data_disks + [d for d in disks if d["path"] in explicit and d not in data_disks]

        if not disks:
            raise RuntimeError("Unable to read disk usage with df -P -k")

        info.disks = disks
        # Preserve legacy summary fields for overview/API compatibility.
        unique = {}
        for d in disks:
            unique[(d["filesystem"], d["mount"])] = d
        summary = list(unique.values())
        info.disk_total = sum(d["total"] for d in summary)
        info.disk_used = sum(d["used"] for d in summary)
        info.disk_pct = (100.0 * info.disk_used / info.disk_total) if info.disk_total else 0.0

    def _processes(self) -> tuple[list[dict], list[dict]]:
        raw = self.ssh.exec(self._PS_CMD, timeout=8)
        running = []
        queued = []
        for line in raw.splitlines():
            p = line.split(None, 6)
            if len(p) < 7:
                continue
            user, pid, cpu, mem, etime, state, cmd = p
            if user in {"daemon", "nobody", "systemd-network", "systemd-resolve"}:
                continue
            if cmd.startswith(("ps ", "head ", "/bin/sh -c", "bash -c")):
                continue
            item = {
                "user": user,
                "pid": pid,
                "cpu": _safe_float(cpu),
                "mem": _safe_float(mem),
                "etime": etime,
                "cmd": cmd,
                "gpu": "●" if pid in self._gpu_pids else "—",
                "state": state,
            }
            if state.startswith(("T", "Z")):
                queued.append(item)
            else:
                running.append(item)
        return running[:20], queued[:10]


# ═══════════════════════════════════════════════════════════════════════
#  Background polling thread
# ═══════════════════════════════════════════════════════════════════════

class MonitorThread(QThread):
    """Polls one server in a loop and emits Qt signals."""

    data_ready      = pyqtSignal(str, object)   # (server_name, ServerInfo)
    status_changed  = pyqtSignal(str, str)       # (server_name, status_str)
    conn_lost       = pyqtSignal(str)
    conn_restored   = pyqtSignal(str)

    def __init__(self, cfg: dict, interval: float = 3.0, parent=None):
        super().__init__(parent)
        self.cfg      = cfg
        self.interval = interval
        self._stop    = False
        self.ssh: SSHConnection | None = None
        self.collector: DataCollector | None = None
        self.info     = ServerInfo(cfg["name"])
        self._last_gpu_activity = time.time()

    def stop(self):
        self._stop = True
        self.requestInterruption()
        if self.ssh:
            self.ssh.disconnect()

    def reconnect(self):
        """Drop the current transport so the loop reloads updated config."""
        ssh = self.ssh
        self.ssh = None
        self.collector = None
        if ssh:
            ssh.disconnect()

    def rename(self, new_name: str):
        self.cfg["name"] = new_name
        self.info.name = new_name

    def _emit_snapshot(self):
        """Emit an object the worker will no longer mutate."""
        name = self.cfg["name"]
        snapshot = self.info
        self.data_ready.emit(name, snapshot)
        self.info = copy.deepcopy(snapshot)

    def run(self):
        retries = 0
        while not self._stop and not self.isInterruptionRequested():
            # ── (re)connect ───────────────────────────────────────────
            if self.ssh is None or not self.ssh.connected():
                self.status_changed.emit(self.cfg["name"], "connecting")
                try:
                    self.ssh = SSHConnection(
                        self.cfg["host"],
                        self.cfg.get("port", 22),
                        self.cfg["user"],
                        key_file=self.cfg.get("key"),
                        password=self.cfg.get("password"),
                        passphrase=self.cfg.get("passphrase"),
                    )
                    self.ssh.connect()
                    self.collector = DataCollector(self.ssh, self.cfg.get("disk_path", "."))
                    self.status_changed.emit(self.cfg["name"], "connected")
                    if retries > 0:
                        self.conn_restored.emit(self.cfg["name"])
                    retries = 0
                except Exception as e:
                    retries += 1
                    wait = min(2 ** retries, 60)
                    self.info.name = self.cfg["name"]
                    self.info.status = "disconnected"
                    self.info.connected = False
                    self.info.error  = str(e)
                    self.conn_lost.emit(self.cfg["name"])
                    self._emit_snapshot()
                    self._sleep(wait)
                    continue

            # ── collect ───────────────────────────────────────────────
            try:
                data = self.collector.collect_all(self.info)
                data.name = self.cfg["name"]

                # ── GPU state change detection ────────────────────────
                now = time.time()
                for g in data.gpus:
                    idx = g["idx"]
                    is_active = g.get("mem_used", 0) >= 20
                    was_active = data._prev_gpu_active.get(idx, is_active)
                    if was_active and not is_active:
                        data._gpu_freed_at[idx] = now
                    elif not was_active and is_active:
                        data._gpu_freed_at.pop(idx, None)
                    data._prev_gpu_active[idx] = is_active
                for idx, freed_at in list(data._gpu_freed_at.items()):
                    if now - freed_at >= 60:
                        gpu_name = ""
                        if idx < len(data.gpus):
                            gpu_name = data.gpus[idx].get("name", "")
                        data.gpu_events.append({
                            "type": "gpu_freed",
                            "gpu_index": idx,
                            "gpu_name": gpu_name,
                            "timestamp": freed_at,
                            "message": f"GPU {idx} idle for 1+ min"
                        })
                        del data._gpu_freed_at[idx]
                # keep last 100 events
                if len(data.gpu_events) > 100:
                    data.gpu_events = data.gpu_events[-100:]

                self.info = data
                self._emit_snapshot()
                # Adaptive refresh: check GPU activity
                any_active = any(g.get("mem_used", 0) >= 20 for g in data.gpus)
                if any_active:
                    self._last_gpu_activity = time.time()
                if time.time() - self._last_gpu_activity > RefreshSettings.idle_threshold:
                    sleep_interval = RefreshSettings.gpu_idle_interval
                else:
                    sleep_interval = RefreshSettings.gpu_active_interval
            except Exception as e:
                self.info.status = "disconnected"
                self.info.connected = False
                self.info.error  = str(e)
                self.conn_lost.emit(self.cfg["name"])
                self._emit_snapshot()
                if self.ssh:
                    self.ssh.disconnect()
                self.ssh = None
                self.collector = None
                self._sleep(3)
                continue

            self._sleep(sleep_interval)

        # cleanup
        if self.ssh:
            self.ssh.disconnect()

    def _sleep(self, secs):
        end = time.time() + secs
        while (not self._stop and not self.isInterruptionRequested()
               and time.time() < end):
            time.sleep(0.25)


# ═══════════════════════════════════════════════════════════════════════
#  Mock / demo data generator
# ═══════════════════════════════════════════════════════════════════════

_MOCK_PROFILES = [
    {
        "cpu_model": "AMD EPYC 7763 64-Core Processor",
        "cpu_cores": 64,
        "mem_total": 512_000,
        "disk_total": 3800,
        "gpus": [
            {"name": "NVIDIA A100-SXM4-80GB", "mem_total": 81920},
            {"name": "NVIDIA A100-SXM4-80GB", "mem_total": 81920},
            {"name": "NVIDIA A100-SXM4-80GB", "mem_total": 81920},
            {"name": "NVIDIA A100-SXM4-80GB", "mem_total": 81920},
        ],
    },
    {
        "cpu_model": "Intel Xeon Platinum 8380",
        "cpu_cores": 40,
        "mem_total": 256_000,
        "disk_total": 1900,
        "gpus": [
            {"name": "NVIDIA RTX 4090", "mem_total": 24576},
            {"name": "NVIDIA RTX 4090", "mem_total": 24576},
        ],
    },
    {
        "cpu_model": "AMD Ryzen 9 7950X 16-Core",
        "cpu_cores": 16,
        "mem_total": 128_000,
        "disk_total": 960,
        "gpus": [
            {"name": "NVIDIA RTX 3090", "mem_total": 24576},
        ],
    },
    {
        "cpu_model": "Intel Xeon Gold 6248R",
        "cpu_cores": 24,
        "mem_total": 192_000,
        "disk_total": 2400,
        "gpus": [
            {"name": "NVIDIA A6000", "mem_total": 49152},
            {"name": "NVIDIA A6000", "mem_total": 49152},
            {"name": "NVIDIA A6000", "mem_total": 49152},
            {"name": "NVIDIA A6000", "mem_total": 49152},
            {"name": "NVIDIA A6000", "mem_total": 49152},
            {"name": "NVIDIA A6000", "mem_total": 49152},
            {"name": "NVIDIA A6000", "mem_total": 49152},
            {"name": "NVIDIA A6000", "mem_total": 49152},
        ],
    },
]

_MOCK_TASKS = [
    ("python train.py --model llama-7b --batch 64 --lr 2e-5", True),
    ("python finetune.py --dataset alpaca --epochs 3", True),
    ("python eval.py --checkpoint best.pt --benchmark", False),
    ("python data_preprocess.py --input /data/raw --output /data/clean", False),
    ("deepspeed --num_gpus=4 train_distributed.py --config ds_config.json", True),
    ("python inference_server.py --port 8080 --model gpt-j-6b", True),
    ("tensorboard --logdir ./runs --port 6006", False),
    ("python run_sweep.py --project nlp-experiment --count 20", True),
    ("jupyter lab --ip=0.0.0.0 --port 8888 --no-browser", False),
    ("python convert_checkpoint.py --from hf --to ggml --model 13b", False),
    ("python train_vae.py --dataset imagenet --latent_dim 512", True),
    ("ray job submit -- python distributed_train.py --world_size 8", True),
    ("python generate.py --prompt_file prompts.json --max_tokens 2048", False),
    ("wandb agent my-project/sweep-42", True),
    ("python quantize.py --model fp16 --bits 4 --method gptq", True),
]

_MOCK_USERS = ["alice", "bob", "charlie", "diana", "eric", "frank"]


class _SmoothRandom:
    """Generates smoothly varying random values using sine + noise."""

    def __init__(self, base, amplitude, min_val=0, max_val=100):
        self.base = base
        self.amplitude = amplitude
        self.min_val = min_val
        self.max_val = max_val
        self.phase = random.uniform(0, math.pi * 2)
        self.freq = random.uniform(0.02, 0.08)
        self.noise_scale = amplitude * 0.3

    def next(self, tick):
        v = self.base + self.amplitude * math.sin(tick * self.freq + self.phase)
        v += random.gauss(0, self.noise_scale)
        return max(self.min_val, min(self.max_val, v))


class MockMonitorThread(QThread):
    """Generates realistic fake data for demo mode."""

    data_ready     = pyqtSignal(str, object)
    status_changed = pyqtSignal(str, str)
    conn_lost      = pyqtSignal(str)
    conn_restored  = pyqtSignal(str)

    def __init__(self, cfg: dict, profile_idx: int = 0,
                 interval: float = 2.0, parent=None):
        super().__init__(parent)
        self.cfg = cfg
        self.interval = interval
        self._stop = False
        self.profile = _MOCK_PROFILES[profile_idx % len(_MOCK_PROFILES)]
        self.info = ServerInfo(cfg["name"])
        self._last_gpu_activity = time.time()

        # smooth random generators
        p = self.profile
        self._cpu_gen = _SmoothRandom(45, 25, 5, 98)
        self._mem_gen = _SmoothRandom(62, 12, 20, 95)
        self._dsk_gen = _SmoothRandom(55, 3, 30, 90)
        self._gpu_gens = [
            _SmoothRandom(random.uniform(30, 85), random.uniform(10, 30), 0, 100)
            for _ in p["gpus"]
        ]
        self._gpu_mem_gens = [
            _SmoothRandom(random.uniform(40, 80), 15, 5, 98)
            for _ in p["gpus"]
        ]
        self._tick = 0

        # pre-select some tasks
        self._active_tasks = random.sample(
            _MOCK_TASKS, min(8, len(_MOCK_TASKS))
        )
        self._task_pids = [
            str(random.randint(10000, 50000)) for _ in self._active_tasks
        ]
        self._task_users = [
            random.choice(_MOCK_USERS) for _ in self._active_tasks
        ]
        self._task_etimes = [
            f"{random.randint(0,48):02d}:{random.randint(0,59):02d}:{random.randint(0,59):02d}"
            for _ in self._active_tasks
        ]

    def stop(self):
        self._stop = True
        self.requestInterruption()

    def rename(self, new_name: str):
        self.cfg["name"] = new_name
        self.info.name = new_name

    def run(self):
        # simulate brief "connecting" phase
        self.status_changed.emit(self.cfg["name"], "connecting")
        self.msleep(random.randint(400, 1000))
        if self._stop or self.isInterruptionRequested():
            return
        self.status_changed.emit(self.cfg["name"], "connected")

        while not self._stop and not self.isInterruptionRequested():
            self._tick += 1
            info = self.info
            p = self.profile

            # CPU
            info.cpu_pct = self._cpu_gen.next(self._tick)
            info.cpu_cores = p["cpu_cores"]
            info.cpu_model = p["cpu_model"]

            # Memory
            info.mem_total = p["mem_total"]
            info.mem_pct = self._mem_gen.next(self._tick)
            info.mem_used = info.mem_total * info.mem_pct / 100

            # Disk
            info.disk_total = p["disk_total"]
            info.disk_pct = self._dsk_gen.next(self._tick)
            info.disk_used = info.disk_total * info.disk_pct / 100

            # GPUs
            now = time.time()
            gpus = []
            for i, gp in enumerate(p["gpus"]):
                util = self._gpu_gens[i].next(self._tick)
                mem_pct = self._gpu_mem_gens[i].next(self._tick)
                mem_used = gp["mem_total"] * mem_pct / 100
                temp = 35 + util * 0.5 + random.gauss(0, 2)
                power = 50 + util * 2.8 + random.gauss(0, 5)
                gpus.append({
                    "idx": i,
                    "name": gp["name"],
                    "util": util,
                    "mem_used": mem_used,
                    "mem_total": gp["mem_total"],
                    "mem_pct": mem_pct,
                    "temp": max(30, temp),
                    "power": max(30, power),
                    "power_lim": 400 if "A100" in gp["name"] else 300,
                })
            info.gpus = gpus
            info.gpu_model = gpus[0]["name"] if gpus else ""

            # history
            while len(info.gpu_history) < len(gpus):
                info.gpu_history.append([])
            info.gpu_history = info.gpu_history[: len(gpus)]
            for i, g in enumerate(gpus):
                DataCollector._append_history(
                    info.gpu_history[i], now, g["util"]
                )

            # tasks (shuffle occasionally)
            if self._tick % 30 == 0:
                n = random.randint(4, min(8, len(_MOCK_TASKS)))
                self._active_tasks = random.sample(_MOCK_TASKS, n)
                self._task_pids = [
                    str(random.randint(10000, 50000)) for _ in self._active_tasks
                ]
                self._task_users = [
                    random.choice(_MOCK_USERS) for _ in self._active_tasks
                ]
                self._task_etimes = [
                    f"{random.randint(0,48):02d}:{random.randint(0,59):02d}:{random.randint(0,59):02d}"
                    for _ in self._active_tasks
                ]

            running = []
            for j, (cmd, is_gpu) in enumerate(self._active_tasks):
                running.append({
                    "user": self._task_users[j],
                    "pid": self._task_pids[j],
                    "cpu": max(0, random.gauss(35 if is_gpu else 8, 15)),
                    "mem": max(0.1, random.gauss(12 if is_gpu else 3, 5)),
                    "etime": self._task_etimes[j],
                    "cmd": cmd,
                    "gpu": "●" if is_gpu else "—",
                })
            running.sort(key=lambda x: -x["cpu"])
            info.running_tasks = running

            # queued (a couple of fake ones)
            info.queued_tasks = [
                {
                    "user": random.choice(_MOCK_USERS),
                    "pid": str(random.randint(50000, 60000)),
                    "cpu": 0.0,
                    "mem": random.uniform(0.1, 1.0),
                    "etime": "00:00:00",
                    "cmd": random.choice(_MOCK_TASKS)[0],
                    "gpu": "—",
                }
                for _ in range(random.randint(1, 3))
            ]

            info.status = "connected"
            info.connected = True
            info.error = ""
            info.last_update = datetime.now()

            info.name = self.cfg["name"]
            self.data_ready.emit(self.cfg["name"], info)
            self.info = copy.deepcopy(info)

            # Adaptive refresh: check GPU activity
            any_active = any(g.get("mem_used", 0) >= 20 for g in info.gpus)
            if any_active:
                self._last_gpu_activity = time.time()
            if time.time() - self._last_gpu_activity > RefreshSettings.idle_threshold:
                sleep_interval = RefreshSettings.gpu_idle_interval
            else:
                sleep_interval = RefreshSettings.gpu_active_interval

            # sleep
            end = time.time() + sleep_interval
            while (not self._stop and not self.isInterruptionRequested()
                   and time.time() < end):
                time.sleep(0.25)


# ═══════════════════════════════════════════════════════════════════════
#  Custom widgets
# ═══════════════════════════════════════════════════════════════════════

class CircleGauge(QWidget):
    """Circular progress gauge rendered with QPainter."""

    def __init__(self, title="", size=140, parent=None):
        super().__init__(parent)
        self.title = title
        self.sz    = size
        self.val   = 0.0
        self.sub   = ""
        self.setFixedSize(size, size + 48)

    def set(self, val, sub=""):
        new_val = _clamp(_safe_float(val), 0, 100)
        new_sub = str(sub)
        if abs(new_val - self.val) < 0.05 and new_sub == self.sub:
            return
        self.val = new_val
        self.sub = new_sub
        self.update()

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)

        m  = 8
        r  = self.sz - 2 * m
        # track
        pen = QPen(QColor(C.BORDER))
        pen.setWidth(8)
        p.setPen(pen)
        p.drawEllipse(m, m, r, r)

        # arc
        color = color_for_pct(self.val)
        pen = QPen(QColor(color))
        pen.setWidth(8)
        pen.setCapStyle(Qt.RoundCap)
        p.setPen(pen)
        span = int(self.val / 100 * 360 * 16)
        p.drawArc(m, m, r, r, 90 * 16, -span)

        # value
        p.setPen(QColor(C.TEXT))
        p.setFont(QFont("Segoe UI", 18, QFont.Bold))
        p.drawText(m, m, r, r,
                   Qt.AlignCenter,
                   f"{self.val:.0f}%")



        # title below
        p.setPen(QColor(C.DIM))
        p.setFont(QFont("Segoe UI", 11))
        p.drawText(0, self.sz, self.width(), 24,
                   Qt.AlignCenter, self.title)

        # sub-label below title
        if self.sub:
            p.setPen(QColor(C.MUTED))
            p.setFont(QFont("Segoe UI", 8))
            p.drawText(0, self.sz + 24, self.width(), 24,
                       Qt.AlignCenter, self.sub)
        p.end()


class UsageBar(QFrame):
    """Horizontal bar with label, percentage and sub-text."""

    def __init__(self, title="", parent=None):
        super().__init__(parent)
        self._value = 0.0
        self._fill_color = C.GREEN
        self.setStyleSheet("background:transparent;")

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(3)

        row = QHBoxLayout()
        self._title = QLabel(title)
        self._title.setStyleSheet(f"color:{C.DIM}; font-size:13px;")
        self._pct = QLabel("0%")
        self._pct.setStyleSheet(f"color:{C.TEXT}; font-size:13px; font-weight:600;")
        row.addWidget(self._title)
        row.addStretch()
        row.addWidget(self._pct)
        lay.addLayout(row)

        self._bar = QFrame()
        self._bar.setFixedHeight(8)
        self._bar.setStyleSheet(
            f"background:{C.BORDER}; border-radius:4px;"
        )
        self._fill = QFrame(self._bar)
        self._fill.setFixedHeight(8)
        self._fill.setStyleSheet(
            f"background:{C.GREEN}; border-radius:4px;"
        )
        lay.addWidget(self._bar)

        self._sub = QLabel("")
        self._sub.setStyleSheet(f"color:{C.MUTED}; font-size:12px;")
        lay.addWidget(self._sub)

    def set(self, val, sub=""):
        val = _clamp(_safe_float(val), 0, 100)
        self._value = val
        self._pct.setText(f"{val:.0f}%")
        w = max(1, int(self._bar.width() * val / 100))
        if self._fill.width() != w:
            self._fill.setFixedWidth(w)
        color = color_for_pct(val)
        if color != self._fill_color:
            self._fill_color = color
            self._fill.setStyleSheet(
                f"background:{color}; border-radius:4px;"
            )
        self._sub.setText(sub)

    def resizeEvent(self, ev):
        super().resizeEvent(ev)
        # re-scale bar on resize
        self._fill.setFixedWidth(
            max(1, int(self._bar.width() * self._value / 100))
        )


class StatCard(QFrame):
    """Compact card showing a single metric."""

    def __init__(self, title="", parent=None):
        super().__init__(parent)
        self.setStyleSheet(
            f"background:{C.CARD}; border-radius:10px; padding:12px;"
        )
        lay = QVBoxLayout(self)
        lay.setContentsMargins(16, 12, 16, 12)
        lay.setSpacing(4)
        self._t = QLabel(title)
        self._t.setStyleSheet(f"color:{C.DIM}; font-size:13px;")
        self._v = QLabel("—")
        self._v.setStyleSheet(f"color:{C.TEXT}; font-size:22px; font-weight:700;")
        self._s = QLabel("")
        self._s.setStyleSheet(f"color:{C.MUTED}; font-size:12px;")
        lay.addWidget(self._t)
        lay.addWidget(self._v)
        lay.addWidget(self._s)

    def set(self, val, sub=""):
        self._v.setText(str(val))
        self._s.setText(sub)


class ServerCard(QFrame):
    """Sidebar item representing one server."""

    clicked = pyqtSignal(str)
    remove_requested = pyqtSignal(str)

    def __init__(self, name, parent=None):
        super().__init__(parent)
        self.name = name
        self._sel = False
        self._hover = False
        self._drag_start = None
        self._gpu_dots: list[QLabel] = []
        self._gpu_util_history: dict[int, list] = {}  # gpu_idx -> [(ts, util), ...]
        self._gpu_idle_since: list[float | None] = []  # per-GPU idle start time
        self.setCursor(Qt.PointingHandCursor)
        self.setFixedHeight(98)
        self.setAttribute(Qt.WA_Hover, True)
        self.setAcceptDrops(True)

        # outer layout: accent bar | content
        outer = QHBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)

        # ── left accent indicator ─────────────────────────────────────
        self._accent = QFrame()
        self._accent.setFixedWidth(3)
        self._accent.setStyleSheet("background:transparent;")
        outer.addWidget(self._accent)

        # ── card body ─────────────────────────────────────────────────
        self._body = QFrame()
        body_lay = QVBoxLayout(self._body)
        body_lay.setContentsMargins(16, 10, 16, 10)
        body_lay.setSpacing(5)

        row1 = QHBoxLayout()
        self._dot = QLabel("●")
        self._dot.setStyleSheet(f"color:{C.MUTED}; font-size:11px;")

        self._name = QLabel(name)
        self._name.setStyleSheet(
            f"color:{C.TEXT}; font-size:15px; font-weight:600;"
        )
        row1.addWidget(self._dot)

        row1.addWidget(self._name, 1)

        # delete button
        self._del_btn = QPushButton("×")
        self._del_btn.setFixedSize(22, 22)
        self._del_btn.setCursor(Qt.PointingHandCursor)
        self._del_btn.setStyleSheet(
            f"QPushButton {{"
            f"  background:transparent; color:{C.MUTED};"
            f"  border:none; border-radius:11px; font-size:14px; font-weight:bold;"
            f"}}"            f"QPushButton:hover {{"
            f"  background:{C.RED}44; color:{C.RED};"
            f"}}"            f"QPushButton:pressed {{"
            f"  background:{C.RED}88;"
            f"}}"
        )
        self._del_btn.clicked.connect(lambda: self.remove_requested.emit(self.name))
        row1.addWidget(self._del_btn)

        body_lay.addLayout(row1)

        self._info = QLabel("Connecting…")
        self._info.setStyleSheet(f"color:{C.DIM}; font-size:13px;")
        body_lay.addWidget(self._info)

        # ── GPU indicator dots row ────────────────────────────────────
        self._gpu_row = QHBoxLayout()
        self._gpu_row.setSpacing(5)
        self._gpu_row.setContentsMargins(0, 0, 0, 0)
        self._gpu_label = QLabel("")
        self._gpu_label.setStyleSheet(f"color:{C.MUTED}; font-size:11px;")
        self._gpu_row.addWidget(self._gpu_label)
        self._gpu_dots_container = QHBoxLayout()
        self._gpu_dots_container.setSpacing(4)
        self._gpu_row.addLayout(self._gpu_dots_container)
        self._gpu_row.addStretch()
        body_lay.addLayout(self._gpu_row)

        outer.addWidget(self._body, 1)

        # apply style AFTER all widgets are created
        self._apply_body_style()

    def set_name(self, new_name):
        """Update the displayed server name."""
        self.name = new_name
        self._name.setText(new_name)

    def _apply_body_style(self):
        if self._sel:
            bg = C.CARD_SELECTED
        elif self._hover:
            bg = C.CARD_HOVER
        else:
            bg = "transparent"
        self._body.setStyleSheet(
            f"background:{bg}; border-radius:0 10px 10px 0;"
            f" margin:2px 4px 2px 0;"
        )
        ac = C.ACCENT if self._sel else "transparent"
        self._accent.setStyleSheet(
            f"background:{ac}; border-radius:0 2px 2px 0;"
            f" margin:8px 0 8px 0;"
        )
        if self._sel:
            self._name.setStyleSheet(
                f"color:#fff; font-size:15px; font-weight:700;"
            )
        else:
            self._name.setStyleSheet(
                f"color:{C.TEXT}; font-size:15px; font-weight:600;"
            )

    def enterEvent(self, _):
        self._hover = True
        self._apply_body_style()

    def leaveEvent(self, _):
        self._hover = False
        self._apply_body_style()

    def set_selected(self, sel):
        self._sel = sel
        self._apply_body_style()

    def _sync_gpu_dots(self, gpus: list[dict]):
        """
        Color logic per GPU:
          Blue   — VRAM used < 20 MiB for 30+ min  → long idle
          Green  — VRAM used < 20 MiB              → idle
          Yellow — VRAM ≥ 20 MiB  AND  20s avg util ≤ 30%
          Red    — VRAM ≥ 20 MiB  AND  20s avg util >  30%
        """
        now = time.time()
        count = len(gpus)

        # add or remove dots
        while len(self._gpu_dots) < count:
            d = QLabel("●")
            d.setStyleSheet(f"color:{C.MUTED}; font-size:12px;")
            d.setFixedSize(18, 18)
            d.setAlignment(Qt.AlignCenter)
            d.setMouseTracking(True)
            d.setAttribute(Qt.WA_AlwaysShowToolTips, True)
            d.setToolTipDuration(15000)
            self._gpu_dots_container.addWidget(d)
            self._gpu_dots.append(d)
        while len(self._gpu_dots) > count:
            d = self._gpu_dots.pop()
            self._gpu_dots_container.removeWidget(d)
            d.deleteLater()

        for i, g in enumerate(gpus):
            mem_used = g.get("mem_used", 0)      # MiB
            util     = g.get("util", 0)           # instantaneous %

            # record sample
            if i not in self._gpu_util_history:
                self._gpu_util_history[i] = []
            self._gpu_util_history[i].append((now, util))
            # keep only last 20 s
            cutoff = now - 20
            self._gpu_util_history[i] = [
                (t, v) for t, v in self._gpu_util_history[i] if t >= cutoff
            ]
            # rolling average
            samples = self._gpu_util_history[i]
            avg_util = (
                sum(v for _, v in samples) / len(samples) if samples else 0
            )

            # ── color decision (per-GPU idle tracking) ────────────────
            if mem_used < 20:
                if i >= len(self._gpu_idle_since):
                    self._gpu_idle_since.append(now)
                elif self._gpu_idle_since[i] is None:
                    self._gpu_idle_since[i] = now
                idle_duration = now - self._gpu_idle_since[i]
                if idle_duration >= 1800:
                    color = C.BLUE
                    status = f"Idle {int(idle_duration//60)}min"
                else:
                    color = C.GREEN
                    status = "Idle"
            else:
                if i < len(self._gpu_idle_since):
                    self._gpu_idle_since[i] = None
                if avg_util > 30:
                    color = C.RED
                    status = f"Busy (avg {avg_util:.0f}%)"
                else:
                    color = C.YELLOW
                    status = f"Low (avg {avg_util:.0f}%)"

            dot = self._gpu_dots[i]
            if dot.property("status_color") != color:
                dot.setStyleSheet(f"color:{color}; font-size:12px;")
                dot.setProperty("status_color", color)
            tooltip = (
                f"GPU {i}: {g.get('name', '')}\n"
                f"VRAM {mem_used:.0f}/{g.get('mem_total', 0):.0f} MiB\n"
                f"Util {util:.0f}% (20s avg {avg_util:.0f}%)\n"
                f"{status}"
            )
            # Updating a QLabel while its tooltip is opening can dismiss the
            # tooltip on frequently-polled busy GPUs. Keep the visible text
            # stable until the pointer leaves the dot.
            if not dot.underMouse() or not dot.toolTip():
                dot.setToolTip(tooltip)

        while len(self._gpu_idle_since) > count:
            self._gpu_idle_since.pop()

        self._gpu_label.setText(f"{count} GPU" if count else "")

    def update_info(self, info: ServerInfo):
        st = info.status
        color = {
            "connected":    C.GREEN,
            "connecting":   C.YELLOW,
            "disconnected": C.RED,
            "error":        C.RED,
        }.get(st, C.MUTED)
        self._dot.setStyleSheet(f"color:{color}; font-size:11px;")

        # ── GPU idle tracking now handled per-GPU inside _sync_gpu_dots ──

        if st == "connected":
            gpu = ""
            if info.gpus:
                u = info.gpus[0]["util"]
                gpu = f" · GPU {u:.0f}%"
            self._info.setText(
                f"CPU {info.cpu_pct:.0f}%{gpu} · "
                f"{len(info.running_tasks)} tasks"
            )
            self._sync_gpu_dots(info.gpus)
        elif st == "connecting":
            self._info.setText("Connecting…")
            self._sync_gpu_dots([])
        else:
            self._info.setText(info.error[:45] or "Disconnected")
            self._sync_gpu_dots([])

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._drag_start = event.pos()
            self.clicked.emit(self.name)

    def mouseMoveEvent(self, event):
        if not (event.buttons() & Qt.LeftButton) or self._drag_start is None:
            return
        if (event.pos() - self._drag_start).manhattanLength() < 20:
            return
        drag = QDrag(self)
        mime = QMimeData()
        mime.setText(f"server:{self.name}")
        drag.setMimeData(mime)
        drag.exec_(Qt.MoveAction)

    def dragEnterEvent(self, event):
        if event.mimeData().hasText() and event.mimeData().text().startswith("server:"):
            event.acceptProposedAction()
            self._body.setStyleSheet(
                f"background:{C.ACCENT}33; border-radius:0 10px 10px 0;"
                f" margin:2px 4px 2px 0; border:1px dashed {C.ACCENT};"
            )

    def dragLeaveEvent(self, _):
        self._apply_body_style()

    def dropEvent(self, event):
        src = event.mimeData().text().replace("server:", "")
        self._apply_body_style()
        if src and src != self.name:
            # find the Dashboard parent
            parent = self.parent()
            while parent and not isinstance(parent, Dashboard):
                parent = parent.parent()
            if parent:
                parent._reorder_server(src, self.name)
        event.acceptProposedAction()


# ═══════════════════════════════════════════════════════════════════════
#  Chart widget that blocks wheel events (avoid scroll conflict)
# ═══════════════════════════════════════════════════════════════════════

if HAS_PYQTGRAPH:
    class _NoWheelPlotWidget(pg.PlotWidget):
        """PlotWidget that ignores mouse wheel to avoid page-scroll conflict."""
        def wheelEvent(self, event):
            event.ignore()


# ═══════════════════════════════════════════════════════════════════════
#  Time range definitions for history chart
# ═══════════════════════════════════════════════════════════════════════

_TIME_RANGES = [
    ("Default", 0),           # 0 = show all buffered data
    ("5 min",   5 * 60),
    ("30 min",  30 * 60),
    ("1 h",     60 * 60),
    ("5 h",     5 * 3600),
    ("24 h",    24 * 3600),
]


# ═══════════════════════════════════════════════════════════════════════
#  Main window
# ═══════════════════════════════════════════════════════════════════════

class Dashboard(QMainWindow):

    api_mutation_requested = pyqtSignal(object)

    def __init__(self, config_path: str, demo: bool = False,
                 api_port: int = 8766):
        super().__init__()
        self.api_mutation_requested.connect(self._handle_api_mutation)
        self._demo = demo
        self._api_port = int(api_port)
        self._closing = False
        self._state_lock = RLock()
        self._api_server = None
        self._api_thread = None
        self.setWindowTitle(
            "SSH GPU Monitor — DEMO MODE" if demo else "SSH GPU Monitor"
        )

        screen = QApplication.primaryScreen()
        available = screen.availableGeometry() if screen else None
        screen_w = available.width() if available else 1600
        screen_h = available.height() if available else 1000
        self._compact_ui = screen_w < 1500 or screen_h < 850
        self.setMinimumSize(
            min(1100, max(900, int(screen_w * 0.60))),
            min(760, max(600, int(screen_h * 0.68))),
        )
        self.resize(
            min(1600, max(1000, int(screen_w * 0.92))),
            min(1000, max(680, int(screen_h * 0.90))),
        )

        self._servers: dict[str, ServerInfo]    = {}
        self._cards:   dict[str, ServerCard]    = {}
        self._threads: dict[str, QThread] = {}
        self._current: str | None = None

        if demo:
            self._cfg = {
                "refresh_interval": 2,
                "servers": [
                    {"name": "A100 Cluster (4×80G)"},
                    {"name": "RTX 4090 Workstation"},
                    {"name": "RTX 3090 Dev Server"},
                    {"name": "A6000 Render Farm (8×)"},
                ],
            }
        else:
            cfg_path = Path(config_path)
            try:
                with open(cfg_path, encoding="utf-8") as f:
                    self._cfg = json.load(f)
                if not isinstance(self._cfg, dict):
                    raise ValueError("config root must be a JSON object")
            except FileNotFoundError:
                self._cfg = {"servers": [], "refresh_interval": 3}
            except (OSError, json.JSONDecodeError, ValueError) as exc:
                backup_path = cfg_path.parent / "config.backup.json"
                try:
                    with open(backup_path, encoding="utf-8") as f:
                        restored_cfg = json.load(f)
                    if not isinstance(restored_cfg, dict):
                        raise ValueError("backup config root must be a JSON object")
                    self._cfg = restored_cfg
                    shutil.copy2(backup_path, cfg_path)
                except (OSError, json.JSONDecodeError, ValueError):
                    self._cfg = {"servers": [], "refresh_interval": 3}
                    self._config_error = str(exc)
            self._cfg_path = str(cfg_path)

            startup_ui = self._cfg.get("ui", {})
            if isinstance(startup_ui, dict) and bool(startup_ui.get("autostart", False)):
                try:
                    self._set_autostart(True)
                except (OSError, ValueError):
                    pass

        self._interval = self._cfg.get("refresh_interval", 3)
        RefreshSettings._config_path = str(cfg_path) if not demo else None
        RefreshSettings.load(self._cfg)

        # ── load task notes (pattern → description mapping) ───────────
        self._task_notes: list[tuple[str, str]] = []  # [(pattern, note), ...]
        notes_path = Path(config_path).parent / "task_notes.json"
        patterns_path = notes_path.parent / "task_note_patterns.json"
        self._task_note_patterns: list[tuple[re.Pattern, str]] = []
        self._command_log_path = notes_path.parent / "command_analysis_queue.jsonl"
        self._seen_command_signatures: set[str] = set()
        if notes_path.exists():
            try:
                with open(notes_path, encoding="utf-8") as f:
                    raw = json.load(f)
                for pattern, note in raw.items():
                    self._task_notes.append((pattern.lower(), str(note)))
            except Exception:
                pass

        if patterns_path.exists():
            try:
                with open(patterns_path, encoding="utf-8") as f:
                    pattern_rules = json.load(f)
                for rule in pattern_rules:
                    pattern = re.compile(str(rule["pattern"]), re.IGNORECASE)
                    note = str(rule["note"]).strip()
                    if note and not note.startswith("疑似"):
                        note = "疑似" + note
                    if note:
                        self._task_note_patterns.append((pattern, note))
            except (OSError, json.JSONDecodeError, KeyError, TypeError, re.error):
                pass

        if self._command_log_path.exists():
            try:
                with open(self._command_log_path, encoding="utf-8") as f:
                    for line in f:
                        try:
                            record = json.loads(line)
                            signature = str(record.get("signature") or "")
                            if signature:
                                self._seen_command_signatures.add(signature)
                        except (json.JSONDecodeError, AttributeError):
                            continue
            except OSError:
                pass

        # demo notes
        if self._demo:
            self._task_notes.extend([
                ("train.py",         "模型训练"),
                ("finetune",         "微调实验"),
                ("eval.py",          "模型评估"),
                ("yolo",             "YOLO11 检测实验"),
                ("deepspeed",        "分布式训练"),
                ("inference_server", "推理服务"),
                ("tensorboard",      "训练监控"),
                ("sweep",            "超参搜索"),
                ("jupyter",          "Jupyter 开发"),
                ("convert",          "模型格式转换"),
                ("vae",              "VAE 训练"),
                ("ray",              "Ray 分布式任务"),
                ("generate",         "文本生成"),
                ("wandb",            "W&B 实验追踪"),
                ("quantize",         "模型量化"),
                ("data_preprocess",  "数据预处理"),
            ])

        self._build_ui()
        self._add_servers()

    @staticmethod
    def _detect_yolo_version(cmd: str) -> str:
        """Extract an explicit YOLO version from command/model/path text."""
        cmd_lower = cmd.lower()
        patterns = (
            r"(?<![a-z0-9])yolo[\s._/-]*v[\s._/-]*(\d{1,2})(?!\d)",
            r"(?<![a-z0-9])yolo[\s._/-]*(\d{1,2})(?!\d)",
        )
        for pattern in patterns:
            match = re.search(pattern, cmd_lower)
            if match:
                return match.group(1)
        return ""

    def _match_note(self, cmd: str) -> str:
        """Match a command to a note, deriving the real YOLO version first."""
        cmd_lower = cmd.lower()

        # A generic task_notes entry such as ``yolo: YOLO11 检测实验`` must
        # not relabel yolov9/yolo9 commands as YOLO11.  Version detection is
        # intentionally performed before ordered keyword matching, because
        # generic entries such as train.py may otherwise win first.
        if "yolo" in cmd_lower:
            version = self._detect_yolo_version(cmd_lower)
            if version:
                return f"YOLOv{version} 检测实验"

        for pattern, note in self._task_notes:
            if pattern in cmd_lower:
                return note
        for pattern, note in self._task_note_patterns:
            if pattern.search(cmd):
                return note
        return ""

    @staticmethod
    def _normalize_command_signature(command: str) -> str:
        """Collapse volatile arguments so repeated jobs produce one queue row."""
        text = str(command or "").replace("\\n", " ")
        text = re.sub(r"\s+", " ", text).strip().lower()
        text = re.sub(r"(?i)(--seed(?:=|\s+))\d+", r"\1<N>", text)
        return text

    @staticmethod
    def _redact_command(command: str) -> str:
        """Remove common inline secrets before persisting a command locally."""
        return re.sub(
            r"(?i)(--(?:api[-_]?key|token|password|secret)(?:=|\s+))\S+",
            r"\1<REDACTED>",
            str(command or ""),
        )

    def _record_unmatched_commands(self, server_name: str, tasks: list[dict]):
        """Append each unseen, unmatched command to the local analysis queue."""
        if self._demo:
            return
        pending = []
        for task in tasks:
            command = str(task.get("cmd") or "").strip()
            guessed_note = self._match_note(command)
            if not command or (guessed_note and not guessed_note.startswith("疑似")):
                continue
            redacted = self._redact_command(command)
            signature = self._normalize_command_signature(redacted)
            if not signature or signature in self._seen_command_signatures:
                continue
            self._seen_command_signatures.add(signature)
            pending.append({
                "first_seen": datetime.now().isoformat(timespec="seconds"),
                "server": server_name,
                "pid": str(task.get("pid") or ""),
                "user": str(task.get("user") or ""),
                "gpu": str(task.get("gpu") or ""),
                "command": redacted,
                "signature": signature,
                "guess": guessed_note,
            })
        if not pending:
            return
        try:
            with open(self._command_log_path, "a", encoding="utf-8") as f:
                for record in pending:
                    f.write(json.dumps(record, ensure_ascii=False) + "\n")
        except OSError:
            pass

    # ── UI construction ───────────────────────────────────────────────

    def _build_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # ── header bar ────────────────────────────────────────────────
        hdr = QFrame()
        hdr.setStyleSheet(f"background:{C.SIDEBAR};")
        hdr.setFixedHeight(64)
        hl = QHBoxLayout(hdr)
        hl.setContentsMargins(28, 0, 28, 0)

        ico = QLabel("⚡")
        ico.setStyleSheet(f"font-size:24px; color:{C.ACCENT};")
        ttl = QLabel("SSH GPU Monitor")
        ttl.setStyleSheet(
            f"color:{C.TEXT}; font-size:19px; font-weight:700;"
        )
        self._clock = QLabel()
        self._clock.setStyleSheet(f"color:{C.DIM}; font-size:13px;")

        hl.addWidget(ico)
        hl.addWidget(ttl)
        if self._demo:
            badge = QLabel("DEMO")
            badge.setStyleSheet(
                f"background:{C.ACCENT}; color:#fff; font-size:10px;"
                f" font-weight:700; padding:3px 8px; border-radius:4px;"
                f" letter-spacing:1px; margin-left:6px;"
            )
            hl.addWidget(badge)
        hl.addStretch()
        hl.addWidget(self._clock)
        root.addWidget(hdr)

        # ── body ──────────────────────────────────────────────────────
        body = QHBoxLayout()
        body.setContentsMargins(0, 0, 0, 0)
        body.setSpacing(0)

        # sidebar
        self._sidebar = QFrame()
        self._sidebar.setStyleSheet(f"background:{C.SIDEBAR};")
        self._sidebar.setFixedWidth(260 if self._compact_ui else 300)
        sl = QVBoxLayout(self._sidebar)
        sl.setContentsMargins(12, 16, 12, 16)
        sl.setSpacing(8)

        # ── overview button ───────────────────────────────────────────
        self._overview_btn = QPushButton("📊  Overview")
        self._overview_btn.setCursor(Qt.PointingHandCursor)
        self._overview_btn.setFixedHeight(42)
        self._overview_btn.setStyleSheet(
            f"QPushButton {{"
            f"  background:{C.CARD_SELECTED}; color:#fff;"
            f"  border:none; border-radius:8px;"
            f"  font-size:14px; font-weight:700; text-align:left;"
            f"  padding:0 16px;"
            f"}}"
            f"QPushButton:hover {{ background:{C.ACCENT}; }}"
        )
        self._overview_btn.clicked.connect(self._show_overview)
        sl.addWidget(self._overview_btn)

        srv_lbl = QLabel("SERVERS")
        srv_lbl.setStyleSheet(
            f"color:{C.MUTED}; font-size:11px; letter-spacing:2px;"
            f" padding:0 6px; font-weight:600; margin-top:6px;"
        )
        sl.addWidget(srv_lbl)
        # Keep each server card at its intended height. Without a dedicated
        # scroll area, a long server list is vertically compressed and the
        # third row (per-GPU status dots) gets clipped from earlier cards.
        self._server_list_scroll = QScrollArea()
        self._server_list_scroll.setWidgetResizable(True)
        self._server_list_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self._server_list_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarAsNeeded)
        self._server_list_scroll.setStyleSheet(
            f"QScrollArea {{ background:{C.SIDEBAR}; border:none; }}"
        )
        self._server_list_widget = QWidget()
        self._server_list_widget.setStyleSheet(f"background:{C.SIDEBAR};")
        self._slist = QVBoxLayout(self._server_list_widget)
        self._slist.setContentsMargins(0, 0, 0, 0)
        self._slist.setSpacing(6)
        self._slist.setAlignment(Qt.AlignTop)
        self._server_list_scroll.setWidget(self._server_list_widget)
        sl.addWidget(self._server_list_scroll, 1)
        body.addWidget(self._sidebar)

        # separator
        sep = QFrame()
        sep.setFixedWidth(1)
        sep.setStyleSheet(f"background:{C.BORDER};")
        body.addWidget(sep)

        # detail area
        self._scroll = QScrollArea()
        self._scroll.setWidgetResizable(True)
        self._scroll.setStyleSheet(
            f"background:{C.BG}; border:none;"
        )
        self._detail = QWidget()
        self._detail.setStyleSheet(f"background:{C.BG};")
        self._dl = QVBoxLayout(self._detail)
        margin_x = 20 if self._compact_ui else 32
        self._dl.setContentsMargins(margin_x, 20, margin_x, 20)
        self._dl.setSpacing(20)
        self._scroll.setWidget(self._detail)
        body.addWidget(self._scroll)

        root.addLayout(body)

        # ── placeholder ───────────────────────────────────────────────
        self._placeholder = QWidget()
        pl = QVBoxLayout(self._placeholder)
        pl.setAlignment(Qt.AlignCenter)
        pi = QLabel("🖥️")
        pi.setStyleSheet("font-size:52px;")
        pi.setAlignment(Qt.AlignCenter)
        pt = QLabel("Select a server to view details")
        pt.setStyleSheet(f"color:{C.DIM}; font-size:16px; margin-top:10px;")
        pt.setAlignment(Qt.AlignCenter)
        pl.addWidget(pi)
        pl.addWidget(pt)
        self._dl.addWidget(self._placeholder)

        # clock timer
        self._ctimer = QTimer()
        self._ctimer.timeout.connect(self._tick)
        self._ctimer.start(1000)
        self._tick()

    def _add_servers(self):
        seen_names = set()
        for idx, cfg in enumerate(self._cfg.get("servers", [])):
            if not isinstance(cfg, dict):
                continue
            name = str(cfg.get("name") or cfg.get("host") or "").strip()
            if not name or name in seen_names:
                continue
            if not self._demo and (not cfg.get("host") or not cfg.get("user")):
                continue
            cfg["name"] = name
            seen_names.add(name)
            info = ServerInfo(name)
            self._servers[name] = info

            card = ServerCard(name)
            card.clicked.connect(self._on_card_click)
            card.remove_requested.connect(self._remove_server)
            self._cards[name] = card
            self._slist.addWidget(card)

            if self._demo:
                t = MockMonitorThread(cfg, profile_idx=idx,
                                      interval=self._interval, parent=self)
            else:
                t = MonitorThread(cfg, interval=self._interval, parent=self)
            t.data_ready.connect(self._on_data)
            t.status_changed.connect(self._on_status)
            t.conn_lost.connect(self._on_connection_lost)
            self._threads[name] = t

        # auto-select first
        if self._cards:
            first = next(iter(self._cards))
            self._on_card_click(first)

        # HTTP API (port 8765)
        self._start_api()

        # start threads after UI is ready
        QTimer.singleShot(100, self._start_threads)

    def _start_threads(self):
        for t in self._threads.values():
            if not t.isRunning():
                t.start()

    def _on_connection_lost(self, name):
        card = self._cards.get(name)
        info = self._servers.get(name)
        if card is not None and info is not None:
            card.update_info(info)

    # ── detail view (created once per server, reused) ─────────────────

    _detail_views: dict  # will hold per-server detail widgets

    def _ensure_detail(self, name: str):
        """Lazily create the detail view for a server."""
        if not hasattr(self, "_detail_views"):
            self._detail_views = {}
        if name in self._detail_views:
            return

        w = QWidget()
        w.setStyleSheet(f"background:{C.BG};")
        lay = QVBoxLayout(w)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(18)

        # title
        title = QLabel(name)
        title.setStyleSheet(
            f"color:{C.TEXT}; font-size:24px; font-weight:700;"
        )
        lay.addWidget(title)

        # ── gauge row ─────────────────────────────────────────────────
        gauges_widget = QWidget()
        gauges_widget.setStyleSheet("background:transparent;")
        gauges = QHBoxLayout(gauges_widget)
        gauges.setContentsMargins(0, 0, 0, 0)
        gauges.setSpacing(28)
        gauges.setAlignment(Qt.AlignLeft)

        detail_gauge_size = 112 if self._compact_ui else 140
        g_cpu = CircleGauge("CPU", detail_gauge_size)
        g_mem = CircleGauge("Memory", detail_gauge_size)

        # Per-GPU circle gauges container
        gpu_gauge_row = QHBoxLayout()
        gpu_gauge_row.setSpacing(12)
        gpu_gauge_row.setAlignment(Qt.AlignLeft)
        gpu_gauges: list[CircleGauge] = []
        gpu_gauge_row_widget = QWidget()
        gpu_gauge_row_widget.setStyleSheet("background:transparent;")
        gpu_gauge_row_widget.setLayout(gpu_gauge_row)

        g_dsk = CircleGauge("Disk", detail_gauge_size)

        for g in (g_cpu, g_mem):
            gauges.addWidget(g)
        gauges.addWidget(gpu_gauge_row_widget)
        gauges.addWidget(g_dsk)
        gauges.addStretch()
        lay.addWidget(gauges_widget)

        # ── instantaneous usage bars ──────────────────────────────────
        bars_frame = QFrame()
        bars_frame.setStyleSheet(
            f"background:{C.CARD}; border-radius:12px;"
        )
        bl = QVBoxLayout(bars_frame)
        bl.setContentsMargins(24, 20, 24, 20)
        bl.setSpacing(12)

        bhdr = QLabel("Instantaneous Usage")
        bhdr.setStyleSheet(
            f"color:{C.TEXT}; font-size:15px; font-weight:600;"
            f" margin-bottom:4px;"
        )
        bl.addWidget(bhdr)

        bars: dict[str, UsageBar] = {}
        for label in ("CPU", "Memory", "Disk"):
            b = UsageBar(label)
            bars[label] = b
            bl.addWidget(b)

        gpu_bars: list[UsageBar] = []   # filled dynamically
        disk_bars: list[UsageBar] = []  # one bar per /data* mount
        disk_grid_frame = QFrame()
        disk_grid_frame.setStyleSheet("background:transparent;")
        disk_grid = QGridLayout(disk_grid_frame)
        disk_grid.setContentsMargins(0, 0, 0, 0)
        disk_grid.setHorizontalSpacing(12 if self._compact_ui else 16)
        disk_grid.setVerticalSpacing(10)
        bl.addWidget(disk_grid_frame)
        lay.addWidget(bars_frame)

        # ── GPU history chart ─────────────────────────────────────────
        chart_widget = None
        chart_curves = []
        chart_no_pg_label = None
        chart_range = [0]   # mutable container so callbacks can modify
        chart_range_btns: list = []
        chart_last_signature = [None]
        chart_legend = None

        if HAS_PYQTGRAPH:
            pg.setConfigOptions(antialias=False)
            chart_widget = _NoWheelPlotWidget()
            chart_widget.setBackground(C.CHART_BG)
            chart_widget.setMinimumHeight(250)
            chart_widget.setMaximumHeight(320)
            chart_widget.setLabel("left", "Usage", units="%")
            chart_widget.setYRange(0, 105, padding=0)
            chart_widget.showGrid(x=True, y=True, alpha=0.15)
            chart_widget.getAxis("left").setPen(QColor(C.BORDER))
            chart_widget.getAxis("bottom").setPen(QColor(C.BORDER))
            chart_widget.getAxis("left").setTextPen(QColor(C.DIM))
            chart_widget.getAxis("bottom").setTextPen(QColor(C.DIM))
            chart_widget.getAxis("left").setStyle(tickLength=-5)
            chart_widget.getAxis("left").setTickFont(QFont("Segoe UI", 10))
            chart_widget.getAxis("bottom").setTickFont(QFont("Segoe UI", 10))
            chart_widget.setDownsampling(auto=True, mode="peak")
            chart_widget.setClipToView(True)
            chart_legend = chart_widget.addLegend(offset=(60, 10))

            # header row: title on left, time-range buttons on right
            hdr_row = QHBoxLayout()
            hdr2 = QLabel("GPU Usage History")
            hdr2.setStyleSheet(
                f"color:{C.TEXT}; font-size:15px; font-weight:600;"
            )
            hdr_row.addWidget(hdr2)
            hdr_row.addStretch()

            # ── time-range buttons ────────────────────────────────────
            _BTN_CSS = (
                f"QPushButton {{"
                f"  background:{C.CARD}; color:{C.DIM};"
                f"  border:1px solid {C.BORDER}; border-radius:5px;"
                f"  padding:5px 14px; font-size:12px; font-weight:600;"
                f"}}"
                f"QPushButton:hover {{"
                f"  background:{C.CARD_HOVER}; color:{C.TEXT};"
                f"}}"
            )
            _BTN_CSS_ACTIVE = (
                f"QPushButton {{"
                f"  background:{C.ACCENT}; color:#fff;"
                f"  border:1px solid {C.ACCENT}; border-radius:5px;"
                f"  padding:5px 14px; font-size:12px; font-weight:700;"
                f"}}"
                f"QPushButton:hover {{"
                f"  background:{C.ACCENT2};"
                f"}}"
            )

            def _on_range_click(idx):
                new_range = _TIME_RANGES[idx][1]
                if chart_range[0] == new_range:
                    return
                chart_range[0] = new_range
                chart_last_signature[0] = None
                for j, btn in enumerate(chart_range_btns):
                    btn.setStyleSheet(
                        _BTN_CSS_ACTIVE if j == idx else _BTN_CSS
                    )
                # Redraw immediately instead of waiting for the next SSH poll.
                QTimer.singleShot(0, lambda n=name: self._redraw_history_chart(n))

            for idx, (label, _) in enumerate(_TIME_RANGES):
                btn = QPushButton(label)
                btn.setCursor(Qt.PointingHandCursor)
                btn.setStyleSheet(
                    _BTN_CSS_ACTIVE if idx == 0 else _BTN_CSS
                )
                btn.clicked.connect(lambda checked, i=idx: _on_range_click(i))
                chart_range_btns.append(btn)
                hdr_row.addWidget(btn)

            lay.addLayout(hdr_row)
            lay.addWidget(chart_widget)
        else:
            chart_no_pg_label = QLabel(
                "ℹ Install pyqtgraph for history chart: "
                "pip install pyqtgraph"
            )
            chart_no_pg_label.setStyleSheet(
                f"color:{C.DIM}; font-size:12px; padding:20px;"
            )
            lay.addWidget(chart_no_pg_label)

        # ── Running tasks ─────────────────────────────────────────────
        rh = QLabel("Running Tasks")
        rh.setStyleSheet(
            f"color:{C.TEXT}; font-size:16px; font-weight:600;"
            f" margin-top:4px;"
        )
        lay.addWidget(rh)

        rtable = QTableWidget(0, 8)
        rtable.setHorizontalHeaderLabels(
            ["PID", "User", "CPU%", "Mem%", "GPU", "Command", "Time", "Note"]
        )
        rtable.horizontalHeader().setStretchLastSection(True)
        rtable.horizontalHeader().setSectionResizeMode(
            5, QHeaderView.Stretch
        )
        rtable.verticalHeader().setVisible(False)
        rtable.setAlternatingRowColors(True)
        rtable.setEditTriggers(QTableWidget.NoEditTriggers)
        rtable.setSelectionBehavior(QTableWidget.SelectRows)
        rtable.setMinimumHeight(220)
        _style_table(rtable)
        rtable.cellDoubleClicked.connect(
            lambda row, _col, table=rtable: self._copy_task_row(table, row)
        )
        lay.addWidget(rtable)

        # ── Queued tasks ──────────────────────────────────────────────
        qh = QLabel("Queued Tasks")
        qh.setStyleSheet(
            f"color:{C.TEXT}; font-size:16px; font-weight:600;"
        )
        lay.addWidget(qh)

        qtable = QTableWidget(0, 8)
        qtable.setHorizontalHeaderLabels(
            ["PID", "User", "CPU%", "Mem%", "State", "Command", "Time", "Note"]
        )
        qtable.horizontalHeader().setStretchLastSection(True)
        qtable.horizontalHeader().setSectionResizeMode(
            5, QHeaderView.Stretch
        )
        qtable.verticalHeader().setVisible(False)
        qtable.setAlternatingRowColors(True)
        qtable.setEditTriggers(QTableWidget.NoEditTriggers)
        qtable.setSelectionBehavior(QTableWidget.SelectRows)
        qtable.setMinimumHeight(150)
        _style_table(qtable)
        qtable.cellDoubleClicked.connect(
            lambda row, _col, table=qtable: self._copy_task_row(table, row)
        )
        lay.addWidget(qtable)

        lay.addStretch()

        # store references
        self._detail_views[name] = {
            "widget": w,
            "title": title,
            "g_cpu": g_cpu, "g_mem": g_mem,
            "gauge_size": detail_gauge_size,
            "gauges_widget": gauges_widget,
            "gpu_gauges": gpu_gauges,
            "gpu_gauge_row": gpu_gauge_row,
            "g_dsk": g_dsk,
            "bars": bars,
            "gpu_bars": gpu_bars,
            "disk_bars": disk_bars,
            "disk_grid": disk_grid,
            "bars_frame": bars_frame,
            "chart": chart_widget,
            "chart_curves": chart_curves,
            "chart_legend": chart_legend,
            "chart_range": chart_range,
            "chart_last_signature": chart_last_signature,
            "rtable": rtable,
            "qtable": qtable,
            "rtable_sig": None,
            "qtable_sig": None,
        }

    # ── signal handlers ───────────────────────────────────────────────

    def _on_card_click(self, name):
        if name not in self._servers:
            return
        self._current = name
        for n, c in self._cards.items():
            c.set_selected(n == name)

        # deselect overview
        self._overview_btn.setStyleSheet(
            f"QPushButton {{"
            f"  background:{C.CARD_SELECTED}; color:#fff;"
            f"  border:none; border-radius:8px;"
            f"  font-size:14px; font-weight:700; text-align:left;"
            f"  padding:0 16px;"
            f"}}"
            f"QPushButton:hover {{ background:{C.ACCENT}; }}"
        )

        # hide placeholder
        self._placeholder.hide()

        # hide overview widget
        if hasattr(self, "_overview_widget"):
            self._dl.removeWidget(self._overview_widget)
            self._overview_widget.setParent(None)

        # hide all detail views, show selected
        if hasattr(self, "_detail_views"):
            for v in self._detail_views.values():
                self._dl.removeWidget(v["widget"])
                v["widget"].setParent(None)

        self._ensure_detail(name)
        view = self._detail_views[name]
        self._dl.insertWidget(0, view["widget"])
        view["widget"].show()

        # initial data push
        if name in self._servers:
            self._refresh_detail(name, self._servers[name])

    def _on_data(self, name, info: ServerInfo):
        if self._closing or name not in self._threads:
            return
        with self._state_lock:
            self._servers[name] = info
        self._record_unmatched_commands(
            name, info.running_tasks + info.queued_tasks
        )
        if name in self._cards:
            self._cards[name].update_info(info)
        if name == self._current:
            self._refresh_detail(name, info)
        # update overview card data without rebuilding
        if self._current is None and hasattr(self, "_overview_widget"):
            self._update_overview_card(name, info)

    def _on_status(self, name, status):
        with self._state_lock:
            if name in self._servers:
                self._servers[name].status = status

    def _copy_task_row(self, table: QTableWidget, row: int):
        """Copy all visible fields from one task row to the clipboard."""
        if row < 0 or row >= table.rowCount():
            return
        headers = []
        values = []
        for column in range(table.columnCount()):
            header = table.horizontalHeaderItem(column)
            item = table.item(row, column)
            headers.append(header.text() if header else f"Column {column + 1}")
            # Command cells display a shortened value; tooltip stores the full command.
            if column == 5 and item and item.toolTip():
                values.append(item.toolTip())
            else:
                values.append(item.text() if item else "")
        text = "\n".join(f"{key}: {value}" for key, value in zip(headers, values))
        QApplication.clipboard().setText(text)
        table.setToolTip("Copied this task row to clipboard")
        QTimer.singleShot(1200, lambda t=table: t.setToolTip(""))

    # ── overview page ─────────────────────────────────────────────────

    def _show_overview(self):
        self._current = None
        for c in self._cards.values():
            c.set_selected(False)
        self._overview_btn.setStyleSheet(
            f"QPushButton {{"
            f"  background:{C.ACCENT}; color:#fff;"
            f"  border:none; border-radius:8px;"
            f"  font-size:14px; font-weight:700; text-align:left;"
            f"  padding:0 16px;"
            f"}}"
            f"QPushButton:hover {{ background:{C.ACCENT2}; }}"
        )
        # hide placeholder and all detail views
        self._placeholder.hide()
        if hasattr(self, "_detail_views"):
            for v in self._detail_views.values():
                self._dl.removeWidget(v["widget"])
                v["widget"].setParent(None)
        # show overview
        if not hasattr(self, "_overview_widget"):
            self._build_overview()
        self._refresh_overview()
        self._dl.insertWidget(0, self._overview_widget)
        self._overview_widget.show()
        # Geometry is only reliable after the widget enters the visible layout.
        self._overview_signature = None
        QTimer.singleShot(0, self._refresh_overview)
        QTimer.singleShot(80, self._refresh_overview)

    def _build_overview(self):
        w = QWidget()
        w.setStyleSheet(f"background:{C.BG};")
        lay = QVBoxLayout(w)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(20)

        title = QLabel("📊  Server Overview")
        title.setStyleSheet(
            f"color:{C.TEXT}; font-size:24px; font-weight:700;"
        )
        lay.addWidget(title)

        # ── Control panel ─────────────────────────────────────────────
        ctrl_frame = QFrame()
        ctrl_frame.setStyleSheet(
            f"background:{C.CARD}; border-radius:10px; padding:12px;"
        )
        ctrl_lay = QHBoxLayout(ctrl_frame)
        ctrl_lay.setContentsMargins(18, 10, 18, 10)
        ctrl_lay.setSpacing(18)

        _combo_css = (
            f"QComboBox {{"
            f"  background:{C.SIDEBAR}; color:{C.TEXT};"
            f"  border:1px solid {C.BORDER}; border-radius:6px;"
            f"  padding:5px 10px; font-size:12px; min-width:70px;"
            f"}}"
            f"QComboBox::drop-down {{"
            f"  border:none; width:20px;"
            f"}}"
            f"QComboBox QAbstractItemView {{"
            f"  background:{C.SIDEBAR}; color:{C.TEXT};"
            f"  selection-background-color:{C.ACCENT};"
            f"  border:1px solid {C.BORDER};"
            f"}}"
        )

        def _make_combo(label_text, options, current_val, setter):
            lbl = QLabel(label_text)
            lbl.setStyleSheet(f"color:{C.DIM}; font-size:12px; font-weight:600;")
            ctrl_lay.addWidget(lbl)
            combo = QComboBox()
            combo.setStyleSheet(_combo_css)
            for opt in options:
                if isinstance(opt, tuple):
                    display, value = opt
                else:
                    display, value = str(opt), opt
                combo.addItem(display, value)
            # Select current value
            idx = combo.findData(current_val)
            if idx >= 0:
                combo.setCurrentIndex(idx)
            combo.currentIndexChanged.connect(
                lambda ci, s=setter: s(combo.itemData(ci)) if combo.itemData(ci) is not None else None
            )
            ctrl_lay.addWidget(combo)
            return combo

        _make_combo(
            "GPU Poll:", [("1s",1), ("3s",3), ("5s",5), ("10s",10)],
            RefreshSettings.gpu_active_interval,
            lambda v: setattr(RefreshSettings, 'gpu_active_interval', v) or RefreshSettings._auto_save()
        )
        _make_combo(
            "Disk Poll:", [("10s",10), ("30s",30), ("1m",60), ("2m",120), ("5h",18000)],
            RefreshSettings.disk_interval,
            lambda v: setattr(RefreshSettings, 'disk_interval', v) or RefreshSettings._auto_save()
        )
        _make_combo(
            "Idle Poll:", [("30s",30), ("1m",60), ("2m",120), ("5m",300)],
            RefreshSettings.gpu_idle_interval,
            lambda v: setattr(RefreshSettings, 'gpu_idle_interval', v) or RefreshSettings._auto_save()
        )
        _make_combo(
            "Task Poll:", [("3s",3), ("6s",6), ("10s",10), ("30s",30), ("1m",60)],
            RefreshSettings.process_interval,
            lambda v: setattr(RefreshSettings, 'process_interval', v) or RefreshSettings._auto_save()
        )

        ctrl_lay.addStretch()

        # SSH config import button
        ssh_btn = QPushButton("刷新")
        ssh_btn.setCursor(Qt.PointingHandCursor)
        ssh_btn.setFixedHeight(32)
        ssh_btn.setStyleSheet(
            f"QPushButton {{"
            f"  background:{C.CARD_SELECTED}; color:{C.TEXT};"
            f"  border:1px solid {C.BORDER}; border-radius:6px;"
            f"  padding:0 14px; font-size:12px; font-weight:600;"
            f"}}"
            f"QPushButton:hover {{"
            f"  background:{C.ACCENT}; color:#fff;"
            f"}}"
        )
        ssh_btn.clicked.connect(self._import_ssh_config)
        ctrl_lay.addWidget(ssh_btn)

        lay.addWidget(ctrl_frame)

        # grid container for server overview cards
        grid_widget = QWidget()
        grid_widget.setStyleSheet("background:transparent;")
        self._overview_grid = QVBoxLayout(grid_widget)
        self._overview_grid.setSpacing(16)
        self._overview_grid.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(grid_widget)
        lay.addStretch()

        self._overview_widget = w
        self._overview_cards: dict[str, QWidget] = {}

    def _update_overview_card(self, name, info):
        """Update an overview card's gauges without rebuilding."""
        if not hasattr(self, "_overview_cards"):
            return
        card = self._overview_cards.get(name)
        if card is None:
            return

        # status dot
        st = info.status
        dc = {"connected": C.GREEN, "connecting": C.YELLOW,
              "disconnected": C.RED}.get(st, C.MUTED)
        if hasattr(card, "_dot"):
            card._dot.setStyleSheet(f"color:{dc}; font-size:12px;")

        # CPU
        if hasattr(card, "_g_cpu"):
            card._g_cpu.set(info.cpu_pct, f"{info.cpu_cores}c")

        # Memory
        if hasattr(card, "_g_mem"):
            mem_txt = f"{info.mem_used/1024:.0f}/{info.mem_total/1024:.0f}G"
            card._g_mem.set(info.mem_pct, mem_txt)

        # GPU
        if hasattr(card, "_g_gpu"):
            if info.gpus:
                avg = sum(g["util"] for g in info.gpus) / len(info.gpus)
                card._g_gpu.set(avg, f"{len(info.gpus)}× {info.gpus[0]['name'][:12]}")
            else:
                card._g_gpu.set(0, "N/A")

        # Disk
        if hasattr(card, "_g_dsk"):
            card._g_dsk.set(info.disk_pct, f"{len(info.disks)} disks" if len(info.disks) > 1 else f"{info.disk_used:.0f}G")

        # task label
        if hasattr(card, "_task_lbl") and card._task_lbl:
            card._task_lbl.setText(
                f"🟢 {len(info.running_tasks)} running   "
                f"🟡 {len(info.queued_tasks)} queued"
            )

    def _refresh_overview(self):
        if not hasattr(self, "_overview_grid"):
            return

        # Calculate columns from the real scroll viewport. During initial
        # construction overview_widget may still report its tiny sizeHint width.
        viewport_w = self._scroll.viewport().width() if hasattr(self, "_scroll") else 0
        widget_w = self._overview_widget.width() if hasattr(self, "_overview_widget") else 0
        detail_w = self._detail.width() if hasattr(self, "_detail") else 0
        avail_w = max(viewport_w, widget_w, detail_w, self.width() - self._sidebar.width() - 80) - 40
        card_w = 320 if self._compact_ui else 340
        gap = 16
        cols = max(1, (avail_w + gap) // (card_w + gap))
        signature = (cols, tuple(self._servers.keys()))

        # Skip only when both geometry and server membership are unchanged.
        if getattr(self, "_overview_signature", None) == signature:
            return
        self._overview_signature = signature

        # Clear both widgets and nested row layouts; otherwise empty layouts
        # accumulate after every resize/rebuild.
        _clear_layout(self._overview_grid)
        self._overview_cards.clear()

        # create row layout with dynamic column count
        row_layout = None
        col = 0

        for name, info in self._servers.items():
            if col % cols == 0:
                row_layout = QHBoxLayout()
                row_layout.setSpacing(gap)
                row_layout.setAlignment(Qt.AlignLeft)
                self._overview_grid.addLayout(row_layout)

            card = self._make_overview_card(name, info)
            self._overview_cards[name] = card
            row_layout.addWidget(card)
            col += 1

        # fill remaining space in last row
        if row_layout:
            row_layout.addStretch()

    def _make_overview_card(self, name, info: ServerInfo) -> QWidget:
        card = QFrame()
        card.setStyleSheet(
            f"background:{C.CARD}; border-radius:14px; border:none;"
        )
        card.setFixedWidth(320 if self._compact_ui else 340)
        lay = QVBoxLayout(card)
        lay.setContentsMargins(18, 14, 18, 14)
        lay.setSpacing(10)

        # header
        hdr = QHBoxLayout()
        dot = QLabel("●")
        st = info.status
        dc = {"connected": C.GREEN, "connecting": C.YELLOW,
              "disconnected": C.RED}.get(st, C.MUTED)
        dot.setStyleSheet(f"color:{dc}; font-size:12px;")
        nm = QLabel(name)
        nm.setStyleSheet(f"color:{C.TEXT}; font-size:16px; font-weight:700;")
        hdr.addWidget(dot)
        hdr.addWidget(nm, 1)

        # rename button
        rename_btn = QPushButton("✏️")
        rename_btn.setFixedSize(24, 24)
        rename_btn.setCursor(Qt.PointingHandCursor)
        rename_btn.setStyleSheet(
            f"QPushButton {{"
            f"  background:transparent; color:{C.MUTED};"
            f"  border:none; border-radius:12px; font-size:13px;"
            f"}}"
            f"QPushButton:hover {{"
            f"  background:{C.ACCENT}44; color:{C.ACCENT};"
            f"}}"
        )
        rename_btn.clicked.connect(lambda: self._rename_overview_card(name, nm))
        hdr.addWidget(rename_btn)

        # delete button
        del_btn = QPushButton("×")
        del_btn.setFixedSize(24, 24)
        del_btn.setCursor(Qt.PointingHandCursor)
        del_btn.setStyleSheet(
            f"QPushButton {{"
            f"  background:transparent; color:{C.MUTED};"
            f"  border:none; border-radius:12px; font-size:16px; font-weight:bold;"
            f"}}"
            f"QPushButton:hover {{"
            f"  background:{C.RED}44; color:{C.RED};"
            f"}}"
        )
        del_btn.clicked.connect(lambda: self._remove_server(name))
        hdr.addWidget(del_btn)

        lay.addLayout(hdr)

        # gauges row
        g_row = QHBoxLayout()
        g_row.setSpacing(6)
        g_row.setAlignment(Qt.AlignLeft)

        g_cpu = CircleGauge("CPU", 70)
        g_cpu.set(info.cpu_pct, f"{info.cpu_cores}c")

        g_mem = CircleGauge("Memory", 70)
        g_mem.set(info.mem_pct,
                   f"{info.mem_used/1024:.0f}/{info.mem_total/1024:.0f}G")

        g_gpu = CircleGauge("GPU", 70)
        if info.gpus:
            avg = sum(g["util"] for g in info.gpus) / len(info.gpus)
            g_gpu.set(avg, f"{len(info.gpus)}× {info.gpus[0]['name'][:12]}")
        else:
            g_gpu.set(0, "N/A")

        g_dsk = CircleGauge("Disk", 70)
        g_dsk.set(info.disk_pct, f"{len(info.disks)} disks" if len(info.disks) > 1 else f"{info.disk_used:.0f}G")

        # store gauges for live updates
        card._g_cpu = g_cpu
        card._g_mem = g_mem
        card._g_gpu = g_gpu
        card._g_dsk = g_dsk
        card._dot = dot
        card._name_label = nm
        card._task_lbl = None  # will be set below

        for g in (g_cpu, g_mem, g_gpu, g_dsk):
            g_row.addWidget(g)
        g_row.addStretch()
        lay.addLayout(g_row)

        # task summary
        task_lbl = QLabel(
            f"🟢 {len(info.running_tasks)} running   "
            f"🟡 {len(info.queued_tasks)} queued"
        )
        card._task_lbl = task_lbl
        task_lbl.setStyleSheet(f"color:{C.DIM}; font-size:12px;")
        lay.addWidget(task_lbl)

        # click to jump to detail
        card.setCursor(Qt.PointingHandCursor)

        def _click(_, n=name):
            self._on_card_click(n)
            self._overview_btn.setStyleSheet(
                f"QPushButton {{"
                f"  background:{C.CARD_SELECTED}; color:#fff;"
                f"  border:none; border-radius:8px;"
                f"  font-size:14px; font-weight:700; text-align:left;"
                f"  padding:0 16px;"
                f"}}"
                f"QPushButton:hover {{ background:{C.ACCENT}; }}"
            )
        card.mousePressEvent = _click

        return card

    # ── SSH config import ─────────────────────────────────────────────

    def _save_config(self):
        if self._demo or not hasattr(self, "_cfg_path"):
            return False
        config_path = Path(self._cfg_path)
        backup_path = config_path.with_name("config.backup.json")
        temporary_path = config_path.with_name(f".{config_path.name}.tmp")
        try:
            config_path.parent.mkdir(parents=True, exist_ok=True)
            if config_path.is_file():
                shutil.copy2(config_path, backup_path)
            with open(temporary_path, "w", encoding="utf-8") as f:
                json.dump(self._cfg, f, indent=2, ensure_ascii=False)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temporary_path, config_path)
            return True
        except OSError:
            try:
                temporary_path.unlink(missing_ok=True)
            except OSError:
                pass
            return False

    def _rename_overview_card(self, name, name_label):
        """Rename a server via dialog, update config and UI."""
        new_name, ok = QInputDialog.getText(
            None, "重命名服务器", "新名称:", text=name
        )
        if not ok or not new_name.strip() or new_name.strip() == name:
            return
        new_name = new_name.strip()
        if new_name in self._servers:
            return

        for s in self._cfg.get("servers", []):
            if s.get("name") == name:
                s["name"] = new_name
                break

        with self._state_lock:
            info = self._servers.get(name)
            card = self._cards.get(name)
            thread = self._threads.get(name)
            self._servers = {
                (new_name if k == name else k): v
                for k, v in self._servers.items()
            }
            self._cards = {
                (new_name if k == name else k): v
                for k, v in self._cards.items()
            }
            self._threads = {
                (new_name if k == name else k): v
                for k, v in self._threads.items()
            }

        if info:
            info.name = new_name
        if card:
            card.set_name(new_name)
        if thread and hasattr(thread, "rename"):
            thread.rename(new_name)

        if hasattr(self, "_detail_views") and name in self._detail_views:
            view = self._detail_views.pop(name)
            view["title"].setText(new_name)
            self._detail_views[new_name] = view
        if self._current == name:
            self._current = new_name

        name_label.setText(new_name)
        self._overview_signature = None
        self._save_config()
        if self._current is None and hasattr(self, "_overview_widget"):
            self._refresh_overview()

    def _remove_server(self, name):
        """Remove a server from config, UI, and monitoring."""
        if name not in self._servers:
            return

        thread = self._threads.pop(name, None)
        if thread is not None:
            try:
                thread.data_ready.disconnect(self._on_data)
                thread.status_changed.disconnect(self._on_status)
                thread.conn_lost.disconnect(self._on_connection_lost)
            except (TypeError, RuntimeError):
                pass
            thread.stop()
            thread.wait(3000)

        # remove from config
        self._cfg["servers"] = [s for s in self._cfg.get("servers", []) if s.get("name") != name]
        ui = self._cfg.get("ui", {})
        if isinstance(ui, dict) and isinstance(ui.get("hidden_servers"), list):
            ui["hidden_servers"] = [item for item in ui["hidden_servers"] if str(item) != name]
        self._save_config()

        # remove from sidebar
        if name in self._cards:
            self._slist.removeWidget(self._cards[name])
            self._cards[name].deleteLater()
            del self._cards[name]

        # remove from data
        with self._state_lock:
            self._servers.pop(name, None)

        if hasattr(self, "_detail_views") and name in self._detail_views:
            view = self._detail_views.pop(name)
            self._dl.removeWidget(view["widget"])
            view["widget"].deleteLater()

        # if this was the current detail view, go back to placeholder
        if self._current == name:
            self._current = None
            self._placeholder.show()
        self._overview_signature = None
        if self._current is None and hasattr(self, "_overview_widget"):
            self._refresh_overview()

    def _import_ssh_config(self):
        """Read SSH config, merge into config.json, add new servers."""
        ssh_cfg_path = Path.home() / ".ssh" / "config"
        if not ssh_cfg_path.exists():
            return

        # 1. Parse SSH config
        entries = []
        current = {}
        with open(ssh_cfg_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.lower().startswith("host "):
                    if current.get("host"):
                        entries.append(current)
                    current = {"host": line.split(None, 1)[1]}
                elif line.lower().startswith("hostname "):
                    current["hostname"] = line.split(None, 1)[1]
                elif line.lower().startswith("port "):
                    try:
                        current["port"] = int(line.split(None, 1)[1])
                    except ValueError:
                        current["port"] = 22
                elif line.lower().startswith("user "):
                    current["user"] = line.split(None, 1)[1]
                elif line.lower().startswith("identityfile "):
                    current["key"] = line.split(None, 1)[1]
        if current.get("host"):
            entries.append(current)

        # 2. Build new server configs from SSH entries
        new_servers = []
        for e in entries:
            name = e["host"]
            if name == "*":
                continue
            new_servers.append({
                "name": name,
                "host": e.get("hostname", name),
                "port": e.get("port", 22),
                "user": e.get("user", "root"),
                "key": e.get("key"),
            })

        # 3. Merge into existing config, keep existing order.  A copied
        # config may use a different display name for the same SSH endpoint,
        # so deduplicate by both alias and (host, port, user).
        def endpoint(server):
            try:
                port = int(server.get("port", 22))
            except (TypeError, ValueError):
                port = 22
            return (
                str(server.get("host", "")).strip().lower(),
                port,
                str(server.get("user", "root")).strip().lower(),
            )

        configured = [
            s for s in self._cfg.get("servers", []) if isinstance(s, dict)
        ]
        self._cfg["servers"] = configured
        existing_by_name = {
            str(s.get("name", "")): s for s in configured if s.get("name")
        }
        existing_by_endpoint = {endpoint(s): s for s in configured}
        added = []
        updated = []
        for ns in new_servers:
            current = existing_by_name.get(ns["name"])
            if current is None:
                current = existing_by_endpoint.get(endpoint(ns))

            if current is None:
                configured.append(ns)
                added.append(ns)
                existing_by_name[ns["name"]] = ns
                existing_by_endpoint[endpoint(ns)] = ns
                continue

            # Keep the current display name when only the SSH alias differs;
            # update connection details without creating a duplicate card.
            changes = {
                "host": ns["host"],
                "port": ns["port"],
                "user": ns["user"],
            }
            if ns.get("key"):
                changes["key"] = ns["key"]
            if any(current.get(k) != v for k, v in changes.items()):
                current.update(changes)
                updated.append(current)

        # 4. Save config.json
        self._save_config()

        # Existing monitor threads hold the same config dict.  Force a clean
        # reconnect so an updated host, port, user, or key applies now.
        for cfg in updated:
            thread = self._threads.get(cfg.get("name"))
            if isinstance(thread, MonitorThread):
                thread.reconnect()

        # 5. Add new servers to dashboard
        for cfg in added:
            name = cfg["name"]
            info = ServerInfo(name)
            self._servers[name] = info
            card = ServerCard(name)
            card.clicked.connect(self._on_card_click)
            card.remove_requested.connect(self._remove_server)
            self._cards[name] = card
            self._slist.addWidget(card)

            if self._demo:
                t = MockMonitorThread(
                    cfg, profile_idx=len(self._threads) % 4,
                    interval=self._interval, parent=self
                )
            else:
                t = MonitorThread(cfg, interval=self._interval, parent=self)
            t.data_ready.connect(self._on_data)
            t.status_changed.connect(self._on_status)
            t.conn_lost.connect(self._on_connection_lost)
            self._threads[name] = t
            t.start()

        if added:
            self._overview_signature = None
            if self._current is None and hasattr(self, "_overview_widget"):
                self._refresh_overview()

    # ── sidebar drag reorder ──────────────────────────────────────────

    def _reorder_server(self, src_name: str, dst_name: str):
        """Move src_name to the position of dst_name in sidebar."""
        if src_name == dst_name:
            return
        names = list(self._cards.keys())
        if src_name not in names or dst_name not in names:
            return
        src_idx = names.index(src_name)
        dst_idx = names.index(dst_name)

        # remove src and insert at dst position
        names.pop(src_idx)
        names.insert(dst_idx, src_name)

        # rebuild sidebar layout
        for n in names:
            self._slist.removeWidget(self._cards[n])
        for n in names:
            self._slist.addWidget(self._cards[n])

        # also reorder _servers dict and _threads dict
        self._servers = {n: self._servers[n] for n in names}
        self._threads = {n: self._threads[n] for n in names}
        self._cards = {n: self._cards[n] for n in names}

        by_name = {s.get("name"): s for s in self._cfg.get("servers", [])}
        self._cfg["servers"] = [by_name[n] for n in names if n in by_name]
        self._save_config()
        self._overview_signature = None

    # ── detail refresh ────────────────────────────────────────────────

    def _refresh_detail(self, name, info: ServerInfo):
        if not hasattr(self, "_detail_views"):
            return
        if name not in self._detail_views:
            return
        v = self._detail_views[name]

        # gauges
        v["g_cpu"].set(info.cpu_pct, f"{info.cpu_cores} cores")
        v["g_mem"].set(info.mem_pct,
                        f"{info.mem_used/1024:.1f}/{info.mem_total/1024:.1f} GB")

        # Per-GPU circle gauges
        gpu_gauge_row = v["gpu_gauge_row"]
        gpu_gauges = v["gpu_gauges"]
        n_gpus = len(info.gpus)

        # Clear old gauges if GPU count changed
        if len(gpu_gauges) != n_gpus:
            for g in gpu_gauges:
                gpu_gauge_row.removeWidget(g)
                g.deleteLater()
            gpu_gauges.clear()
            for i in range(n_gpus):
                g = CircleGauge(f"GPU {i}", v["gauge_size"])
                gpu_gauges.append(g)
                gpu_gauge_row.addWidget(g)
            gauge_count = n_gpus + 3
            v["gauges_widget"].setMinimumWidth(
                gauge_count * (v["gauge_size"] + 16)
            )

        for i, g_data in enumerate(info.gpus):
            gpu_gauges[i].set(
                g_data["util"],
                f"{g_data['temp']:.0f}°C · {g_data['power']:.0f}W"
            )

        disk_sub = f"{len(info.disks)} disks" if len(info.disks) > 1 else f"{info.disk_used:.0f}/{info.disk_total:.0f} GB"
        v["g_dsk"].set(info.disk_pct, disk_sub)

        # bars
        v["bars"]["CPU"].set(info.cpu_pct,
                              f"{info.cpu_model[:40]}" if info.cpu_model else "")
        v["bars"]["Memory"].set(
            info.mem_pct,
            f"{info.mem_used/1024:.1f} / {info.mem_total/1024:.1f} GB"
        )
        v["bars"]["Disk"].set(
            info.disk_pct,
            f"{info.disk_used:.0f} / {info.disk_total:.0f} GB total"
        )

        bl = v["bars_frame"].layout()
        # Individual data disks. Rebuild only when the discovered path list changes.
        disk_paths = [d["path"] for d in info.disks]
        existing_paths = [getattr(b, "_disk_path", None) for b in v["disk_bars"]]
        if existing_paths != disk_paths:
            for bar in v["disk_bars"]:
                v["disk_grid"].removeWidget(bar)
                bar.deleteLater()
            v["disk_bars"].clear()
            columns = 2 if self._compact_ui else 3
            for index, d in enumerate(info.disks):
                bar = UsageBar(f"Disk {d['path']}")
                bar._disk_path = d["path"]
                v["disk_bars"].append(bar)
                row, column = divmod(index, columns)
                v["disk_grid"].addWidget(bar, row, column)
        for bar, d in zip(v["disk_bars"], info.disks):
            bar.set(d["percent"], f"{d['used']:.1f} / {d['total']:.1f} GB · {d['mount']}")

        # Dynamic GPU bars. Rebuild only when GPU count changes.
        if len(v["gpu_bars"]) != n_gpus:
            for pair in v["gpu_bars"]:
                bl.removeWidget(pair)
                pair.deleteLater()
            v["gpu_bars"].clear()

            for i in range(n_gpus):
                pair = QFrame()
                pair.setStyleSheet("background:transparent;")
                pair_lay = QHBoxLayout(pair)
                pair_lay.setContentsMargins(0, 0, 0, 0)
                pair_lay.setSpacing(12 if self._compact_ui else 20)
                pair._util_bar = UsageBar(f"GPU {i} Util")
                pair._mem_bar = UsageBar(f"GPU {i} VRAM")
                pair_lay.addWidget(pair._util_bar, 1)
                pair_lay.addWidget(pair._mem_bar, 1)
                v["gpu_bars"].append(pair)
                bl.addWidget(pair)

        for pair, g in zip(v["gpu_bars"], info.gpus):
            pair._util_bar.set(
                g["util"], f"{g['temp']:.0f}°C  {g['power']:.0f}W"
            )
            pair._mem_bar.set(
                g["mem_pct"],
                f"{g['mem_used']:.0f} / {g['mem_total']:.0f} MiB",
            )

        # chart
        self._update_history_chart(v, info)

        v["rtable_sig"] = _update_task_table(
            v["rtable"], info.running_tasks, self._match_note,
            queued=False, previous=v["rtable_sig"],
        )
        v["qtable_sig"] = _update_task_table(
            v["qtable"], info.queued_tasks, self._match_note,
            queued=True, previous=v["qtable_sig"],
        )


    def _redraw_history_chart(self, name):
        """Immediately redraw a chart after a range-button click."""
        view = self._detail_views.get(name)
        info = self._servers.get(name)
        if view is not None and info is not None:
            self._update_history_chart(view, info, force=True)

    def _update_history_chart(self, view, info, force=False):
        """Update existing curves with bounded NumPy arrays and minimal work."""
        if not HAS_PYQTGRAPH or not view.get("chart"):
            return

        chart = view["chart"]
        curves = view["chart_curves"]

        if len(curves) != len(info.gpus):
            for curve in curves:
                chart.removeItem(curve)
            curves.clear()
            legend = view.get("chart_legend")
            if legend is not None:
                legend.clear()
            for i in range(len(info.gpus)):
                pen = pg.mkPen(color=C.COLORS[i % len(C.COLORS)], width=2)
                curves.append(chart.plot([], [], pen=pen, name=f"GPU {i}"))
            view["chart_last_signature"][0] = None

        range_secs = view["chart_range"][0]
        now = time.time()
        history_lengths = tuple(
            len(info.gpu_history[i]) if i < len(info.gpu_history) else 0
            for i in range(len(info.gpus))
        )
        signature = (range_secs, history_lengths)
        if not force and view["chart_last_signature"][0] == signature:
            return
        view["chart_last_signature"][0] = signature

        max_points = 360
        cutoff = now - range_secs if range_secs > 0 else None

        chart.setUpdatesEnabled(False)
        try:
            for i, curve in enumerate(curves):
                hist = info.gpu_history[i] if i < len(info.gpu_history) else []
                if not hist:
                    curve.setData([], [])
                    continue

                # Histories are chronological. Find the first visible sample
                # without constructing a second full list of dictionaries.
                start = 0
                if cutoff is not None:
                    lo, hi = 0, len(hist)
                    while lo < hi:
                        mid = (lo + hi) // 2
                        if hist[mid]["t"] < cutoff:
                            lo = mid + 1
                        else:
                            hi = mid
                    start = lo

                count = len(hist) - start
                if count <= 0:
                    curve.setData([], [])
                    continue

                if count > max_points:
                    # Evenly sample at most ``max_points`` without requiring
                    # NumPy.  Keep the final sample so the latest value is
                    # always visible.
                    step = (count - 1) / float(max_points - 1)
                    indices = [start + int(round(k * step))
                               for k in range(max_points)]
                    indices[-1] = len(hist) - 1
                    xs = [(hist[j]["t"] - now) / 60.0 for j in indices]
                    ys = [float(hist[j]["v"]) for j in indices]
                else:
                    visible = hist[start:]
                    xs = [(sample["t"] - now) / 60.0
                          for sample in visible]
                    ys = [float(sample["v"]) for sample in visible]
                curve.setData(xs, ys, skipFiniteCheck=True)
        finally:
            chart.setUpdatesEnabled(True)
            chart.viewport().update()

        chart.setLabel("bottom", "Time", units="min ago")

    # ── misc ──────────────────────────────────────────────────────────

    def _tick(self):
        self._clock.setText(datetime.now().strftime("%Y-%m-%d %H:%M:%S"))

    # === HTTP API ===

    def _start_api(self):
        if self._api_thread and self._api_thread.is_alive():
            return
        self._api_thread = threading.Thread(target=self._run_api, daemon=True)
        self._api_thread.start()

    def _api_config_dict(self):
        settings = self._cfg.get("settings", {})
        if not isinstance(settings, dict):
            settings = {}
        ui = self._cfg.get("ui", {})
        if not isinstance(ui, dict):
            ui = {}
        try:
            font_scale = float(ui.get("font_scale", 1.08))
        except (TypeError, ValueError):
            font_scale = 1.08
        font_scale = max(0.90, min(1.35, font_scale))
        glass_tint = str(ui.get("glass_tint", "clear") or "clear").lower()
        if glass_tint not in {"clear", "ice", "violet", "aqua", "warm"}:
            glass_tint = "clear"
        hidden_servers_value = ui.get("hidden_servers", [])
        if not isinstance(hidden_servers_value, list):
            hidden_servers_value = []
        hidden_servers = list(dict.fromkeys(
            str(name).strip() for name in hidden_servers_value if str(name).strip()
        ))
        window_bounds = None
        window_bounds_value = ui.get("window_bounds")
        if isinstance(window_bounds_value, dict):
            try:
                parsed_bounds = {
                    "x": int(window_bounds_value.get("x", 0)),
                    "y": int(window_bounds_value.get("y", 0)),
                    "width": int(window_bounds_value.get("width", 0)),
                    "height": int(window_bounds_value.get("height", 0)),
                }
                if parsed_bounds["width"] >= 320 and parsed_bounds["height"] >= 240:
                    window_bounds = parsed_bounds
            except (TypeError, ValueError):
                window_bounds = None
        servers = []
        for server in self._cfg.get("servers", []):
            if not isinstance(server, dict):
                continue
            servers.append({
                "name": str(server.get("name", "")),
                "host": str(server.get("host", "")),
                "port": int(server.get("port", 22) or 22),
                "user": str(server.get("user", "root")),
                "key": str(server.get("key", "") or ""),
                "disk_path": str(server.get("disk_path", ".") or "."),
                "has_password": bool(server.get("password")),
            })
        return {
            "settings": {
                "gpu_active_interval": RefreshSettings.gpu_active_interval,
                "gpu_idle_interval": RefreshSettings.gpu_idle_interval,
                "disk_interval": RefreshSettings.disk_interval,
                "process_interval": RefreshSettings.process_interval,
                "idle_threshold": RefreshSettings.idle_threshold,
                "api_port": int(settings.get("api_port", self._api_port)),
            },
            "ui": {
                "wallpaper_path": str(ui.get("wallpaper_path", "") or ""),
                "default_page": str(ui.get("default_page", "home") or "home"),
                "autostart": bool(ui.get("autostart", False)),
                "readability_blur": bool(ui.get("readability_blur", False)),
                "readability_shade": bool(ui.get("readability_shade", ui.get("readability_blur", False))),
                "text_mode": str(ui.get("text_mode", "light") or "light"),
                "top_bar_blur": bool(ui.get("top_bar_blur", True)),
                "bottom_bar_blur": bool(ui.get("bottom_bar_blur", True)),
                "glass_tint": glass_tint,
                "font_scale": font_scale,
                "hidden_servers": hidden_servers,
                "remember_window_bounds": bool(ui.get("remember_window_bounds", False)),
                "window_bounds": window_bounds,
            },
            "servers": servers,
        }

    @staticmethod
    def _history_to_dict(info):
        series = []
        max_points = 720
        for index, history in enumerate(info.gpu_history):
            if len(history) > max_points:
                step = (len(history) - 1) / float(max_points - 1)
                indices = [int(round(i * step)) for i in range(max_points)]
                indices[-1] = len(history) - 1
                points = [history[i] for i in indices]
            else:
                points = list(history)
            gpu_name = ""
            if index < len(info.gpus):
                gpu_name = str(info.gpus[index].get("name", ""))
            series.append({
                "index": index,
                "name": gpu_name,
                "points": [
                    {"timestamp": float(point["t"]), "value": float(point["v"])}
                    for point in points
                ],
            })
        return {"server": info.name, "series": series}

    def _handle_api_mutation(self, request):
        result = request["result"]
        done = request["done"]
        try:
            action = request.get("action")
            payload = request.get("payload") or {}
            if action == "update_settings":
                result.update(self._api_update_settings(payload))
            elif action == "add_server":
                result.update(self._api_add_server(payload))
            elif action == "remove_server":
                result.update(self._api_remove_server(str(payload.get("name", ""))))
            else:
                raise ValueError("Unsupported API action")
        except Exception as exc:
            result.update({"ok": False, "error": str(exc)})
        finally:
            done.set()

    def _api_update_settings(self, payload):
        settings_payload = payload.get("settings", payload)
        if not isinstance(settings_payload, dict):
            raise ValueError("settings must be an object")

        numeric_fields = {
            "gpu_active_interval": (3, 1, 300),
            "gpu_idle_interval": (60, 5, 3600),
            "disk_interval": (30, 5, 21600),
            "process_interval": (6, 2, 300),
            "idle_threshold": (600, 30, 86400),
        }
        settings = self._cfg.get("settings", {})
        if not isinstance(settings, dict):
            settings = {}
        for field, bounds in numeric_fields.items():
            if field not in settings_payload:
                continue
            default, minimum, maximum = bounds
            value = RefreshSettings._bounded_number(
                settings_payload[field], default, minimum, maximum
            )
            settings[field] = value
            setattr(RefreshSettings, field, value)

        restart_required = False
        if "api_port" in settings_payload:
            try:
                api_port = int(settings_payload["api_port"])
            except (TypeError, ValueError):
                raise ValueError("API port must be a number")
            if not 1024 <= api_port <= 65535:
                raise ValueError("API port must be between 1024 and 65535")
            settings["api_port"] = api_port
            restart_required = api_port != self._api_port
        self._cfg["settings"] = settings

        ui_payload = payload.get("ui", {})
        if not isinstance(ui_payload, dict):
            raise ValueError("ui must be an object")
        ui = self._cfg.get("ui", {})
        if not isinstance(ui, dict):
            ui = {}
        if "wallpaper_path" in ui_payload:
            ui["wallpaper_path"] = str(ui_payload.get("wallpaper_path", "") or "")
        if "default_page" in ui_payload:
            page = str(ui_payload.get("default_page", "home")).lower()
            if page not in {"home", "overview", "gpu", "node", "settings"}:
                raise ValueError("Unknown default page")
            ui["default_page"] = page
        if "autostart" in ui_payload:
            enabled = bool(ui_payload["autostart"])
            self._set_autostart(enabled)
            ui["autostart"] = enabled
        if "readability_blur" in ui_payload:
            ui["readability_blur"] = bool(ui_payload["readability_blur"])
        if "readability_shade" in ui_payload:
            ui["readability_shade"] = bool(ui_payload["readability_shade"])
        if "text_mode" in ui_payload:
            text_mode = str(ui_payload.get("text_mode", "light")).lower()
            if text_mode not in {"light", "dark"}:
                raise ValueError("Unknown text color mode")
            ui["text_mode"] = text_mode
        if "top_bar_blur" in ui_payload:
            ui["top_bar_blur"] = bool(ui_payload["top_bar_blur"])
        if "bottom_bar_blur" in ui_payload:
            ui["bottom_bar_blur"] = bool(ui_payload["bottom_bar_blur"])
        if "glass_tint" in ui_payload:
            glass_tint = str(ui_payload.get("glass_tint", "clear")).lower()
            if glass_tint not in {"clear", "ice", "violet", "aqua", "warm"}:
                raise ValueError("Unknown glass tint")
            ui["glass_tint"] = glass_tint
        if "hidden_servers" in ui_payload:
            hidden_servers = ui_payload["hidden_servers"]
            if not isinstance(hidden_servers, list):
                raise ValueError("Hidden servers must be an array")
            ui["hidden_servers"] = list(dict.fromkeys(
                str(name).strip() for name in hidden_servers if str(name).strip()
            ))
        if "remember_window_bounds" in ui_payload:
            ui["remember_window_bounds"] = bool(ui_payload["remember_window_bounds"])
        if "window_bounds" in ui_payload:
            window_bounds = ui_payload["window_bounds"]
            if window_bounds is None:
                ui.pop("window_bounds", None)
            elif not isinstance(window_bounds, dict):
                raise ValueError("Window bounds must be an object")
            else:
                try:
                    parsed_bounds = {
                        "x": int(window_bounds.get("x", 0)),
                        "y": int(window_bounds.get("y", 0)),
                        "width": int(window_bounds.get("width", 0)),
                        "height": int(window_bounds.get("height", 0)),
                    }
                except (TypeError, ValueError):
                    raise ValueError("Window bounds must contain integer values")
                if not 320 <= parsed_bounds["width"] <= 16384:
                    raise ValueError("Window width is out of range")
                if not 240 <= parsed_bounds["height"] <= 16384:
                    raise ValueError("Window height is out of range")
                if not -65536 <= parsed_bounds["x"] <= 65536 or not -65536 <= parsed_bounds["y"] <= 65536:
                    raise ValueError("Window position is out of range")
                ui["window_bounds"] = parsed_bounds
        if "font_scale" in ui_payload:
            try:
                font_scale = float(ui_payload["font_scale"])
            except (TypeError, ValueError):
                raise ValueError("Font scale must be a number")
            if not 0.90 <= font_scale <= 1.35:
                raise ValueError("Font scale must be between 0.90 and 1.35")
            ui["font_scale"] = round(font_scale, 2)
        self._cfg["ui"] = ui
        if not self._save_config():
            raise OSError("Unable to save configuration")
        return {
            "ok": True,
            "restart_required": restart_required,
            "config": self._api_config_dict(),
        }

    def _set_autostart(self, enabled):
        if sys.platform != "win32":
            return
        import winreg

        value_name = "GPU Monitor"
        run_key = r"Software\Microsoft\Windows\CurrentVersion\Run"
        root = Path(self._cfg_path).resolve().parent
        launcher = root / "start_gpu_monitor.ps1"
        packaged_frontend = str(
            os.environ.get("GPU_MONITOR_FRONTEND_EXE", "") or ""
        ).strip()
        frontend_path = (
            Path(packaged_frontend).expanduser().resolve()
            if packaged_frontend else None
        )
        if enabled and not launcher.exists() and not (
            frontend_path and frontend_path.is_file()
        ):
            raise ValueError("Autostart launcher was not found")
        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER, run_key, 0, winreg.KEY_SET_VALUE
        ) as key:
            if enabled:
                if launcher.exists():
                    command = (
                        'powershell.exe -NoLogo -NoProfile -WindowStyle Hidden '
                        f'-File "{launcher}"'
                    )
                else:
                    command = f'"{frontend_path}"'
                winreg.SetValueEx(key, value_name, 0, winreg.REG_SZ, command)
            else:
                try:
                    winreg.DeleteValue(key, value_name)
                except FileNotFoundError:
                    pass

    def _api_add_server(self, payload):
        name = str(payload.get("name", "")).strip()
        host = str(payload.get("host", "")).strip()
        user = str(payload.get("user", "root")).strip() or "root"
        try:
            port = int(payload.get("port", 22))
        except (TypeError, ValueError):
            raise ValueError("SSH port must be a number")
        if not name:
            raise ValueError("Server name is required")
        if not host:
            raise ValueError("Server address is required")
        if not 1 <= port <= 65535:
            raise ValueError("SSH port must be between 1 and 65535")
        if name in self._servers:
            raise ValueError("A server with this name already exists")

        configured = [
            server for server in self._cfg.get("servers", [])
            if isinstance(server, dict)
        ]
        endpoint = (host.lower(), port, user.lower())
        for server in configured:
            current = (
                str(server.get("host", "")).strip().lower(),
                int(server.get("port", 22) or 22),
                str(server.get("user", "root")).strip().lower(),
            )
            if current == endpoint:
                raise ValueError("This SSH endpoint already exists")

        server_cfg = {
            "name": name,
            "host": host,
            "port": port,
            "user": user,
            "disk_path": str(payload.get("disk_path", ".") or ".").strip() or ".",
        }
        password = str(payload.get("password", "") or "")
        key_file = str(payload.get("key", "") or "").strip()
        if password:
            server_cfg["password"] = password
        if key_file:
            server_cfg["key"] = key_file

        configured.append(server_cfg)
        self._cfg["servers"] = configured
        self._save_config()

        info = ServerInfo(name)
        with self._state_lock:
            self._servers[name] = info
        if hasattr(self, "_slist"):
            card = ServerCard(name)
            card.clicked.connect(self._on_card_click)
            card.remove_requested.connect(self._remove_server)
            self._cards[name] = card
            self._slist.addWidget(card)

        if self._demo:
            thread = MockMonitorThread(
                server_cfg, profile_idx=len(self._threads) % 4,
                interval=self._interval, parent=self
            )
        else:
            thread = MonitorThread(server_cfg, interval=self._interval, parent=self)
        thread.data_ready.connect(self._on_data)
        thread.status_changed.connect(self._on_status)
        thread.conn_lost.connect(self._on_connection_lost)
        self._threads[name] = thread
        thread.start()
        self._overview_signature = None
        return {
            "ok": True,
            "server": {
                "name": name, "host": host, "port": port, "user": user,
                "disk_path": server_cfg["disk_path"],
            },
        }

    def _api_remove_server(self, name):
        if not name or name not in self._servers:
            raise ValueError("Server was not found")
        self._remove_server(name)
        return {"ok": True}

    def _run_api(self):
        sv = self

        class APIHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                pass

            def do_GET(self):
                path = urlsplit(self.path).path
                if path == "/api/servers":
                    with sv._state_lock:
                        items = list(sv._servers.items())
                    data = {
                        name: sv._server_to_dict(info) for name, info in items
                    }
                    self._send_json(data)
                elif path == "/api/config":
                    self._send_json(sv._api_config_dict())
                elif path.startswith("/api/servers/") and path.endswith("/history"):
                    encoded_name = path[len("/api/servers/"):-len("/history")]
                    name = unquote(encoded_name.rstrip("/"))
                    with sv._state_lock:
                        info = sv._servers.get(name)
                    if info is not None:
                        self._send_json(sv._history_to_dict(info))
                    else:
                        self._send_error(404, "Server not found")
                elif path.startswith("/api/servers/"):
                    name = unquote(path[len("/api/servers/"):])
                    with sv._state_lock:
                        info = sv._servers.get(name)
                    if info is not None:
                        self._send_json(sv._server_to_dict(info))
                    else:
                        self._send_error(404, "Server not found")
                elif path == "/api/events":
                    events = []
                    with sv._state_lock:
                        items = list(sv._servers.items())
                    for name, info in items:
                        for ev in info.gpu_events:
                            ev_copy = dict(ev)
                            ev_copy["server"] = name
                            events.append(ev_copy)
                    self._send_json(events)
                else:
                    self._send_error(404, "Not found")

            def do_POST(self):
                path = urlsplit(self.path).path
                try:
                    payload = self._read_json()
                except ValueError as exc:
                    self._send_error(400, str(exc))
                    return
                if path == "/api/settings":
                    self._dispatch_mutation("update_settings", payload)
                elif path == "/api/servers":
                    self._dispatch_mutation("add_server", payload)
                else:
                    self._send_error(404, "Not found")

            def do_DELETE(self):
                path = urlsplit(self.path).path
                if path.startswith("/api/servers/"):
                    name = unquote(path[len("/api/servers/"):])
                    self._dispatch_mutation("remove_server", {"name": name})
                else:
                    self._send_error(404, "Not found")

            def do_OPTIONS(self):
                self.send_response(204)
                self._send_cors_headers()
                self.send_header("Content-Length", "0")
                self.end_headers()

            def _read_json(self):
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                except ValueError:
                    raise ValueError("Invalid Content-Length")
                if length <= 0 or length > 1024 * 1024:
                    raise ValueError("Request body must contain JSON")
                try:
                    payload = json.loads(self.rfile.read(length).decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    raise ValueError("Invalid JSON")
                if not isinstance(payload, dict):
                    raise ValueError("JSON body must be an object")
                return payload

            def _dispatch_mutation(self, action, payload):
                done = threading.Event()
                result = {}
                sv.api_mutation_requested.emit({
                    "action": action,
                    "payload": payload,
                    "done": done,
                    "result": result,
                })
                if not done.wait(10):
                    self._send_error(504, "Backend operation timed out")
                    return
                self._send_json(result, 200 if result.get("ok") else 400)

            def _send_cors_headers(self):
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Headers", "Content-Type")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")

            def _send_json(self, data, status=200):
                body = json.dumps(data, default=str, ensure_ascii=False).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self._send_cors_headers()
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _send_error(self, code, msg):
                self._send_json({"ok": False, "error": msg}, code)

        try:
            server = http.server.ThreadingHTTPServer(
                ("127.0.0.1", self._api_port), APIHandler
            )
            server.daemon_threads = True
            self._api_server = server
            server.serve_forever(poll_interval=0.25)
        except OSError:
            self._api_server = None

    def _server_to_dict(self, info):
        def task_to_dict(task):
            command = str(task.get("cmd", "") or "")
            return {
                "pid": str(task.get("pid", "")),
                "user": str(task.get("user", "")),
                "cpu_percent": task.get("cpu", 0),
                "mem_percent": task.get("mem", 0),
                "gpu": str(task.get("gpu", "—") or "—"),
                "state": str(task.get("state", "") or ""),
                "command": command,
                "time": str(task.get("etime", "") or ""),
                "note": str(self._match_note(command) or ""),
            }

        return {
            "name": info.name,
            "status": info.status,
            "connected": info.connected,
            "error": info.error,
            "cpu": {
                "percent": info.cpu_pct,
                "cores": info.cpu_cores,
                "model": info.cpu_model,
            },
            "memory": {
                "used_gb": round(info.mem_used / 1024, 1),
                "total_gb": round(info.mem_total / 1024, 1),
                "percent": info.mem_pct,
            },
            "disk": {
                "used_gb": info.disk_used,
                "total_gb": info.disk_total,
                "percent": info.disk_pct,
                "volumes": info.disks,
            },
            "gpus": [
                {
                    "index": g["idx"],
                    "name": g["name"],
                    "util_percent": g["util"],
                    "memory_used_mib": g["mem_used"],
                    "memory_total_mib": g["mem_total"],
                    "memory_percent": g["mem_pct"],
                    "temp_celsius": g["temp"],
                    "power_watts": g["power"],
                    "power_limit_watts": g["power_lim"],
                }
                for g in info.gpus
            ],
            "running_tasks": [task_to_dict(task) for task in info.running_tasks],
            "queued_tasks": [task_to_dict(task) for task in info.queued_tasks],
            "last_update": str(info.last_update) if info.last_update else None,
        }

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._overview_signature = None
        if self._current is None and hasattr(self, "_overview_widget"):
            self._refresh_overview()

    def closeEvent(self, event):
        self._closing = True
        self._ctimer.stop()
        server = self._api_server
        if server:
            self._api_server = None
            threading.Thread(
                target=self._shutdown_http_server,
                args=(server,),
                daemon=True,
            ).start()
        threads = list(self._threads.values())
        for t in threads:
            t.stop()

        # Keep one shared shutdown deadline.  Waiting five seconds per server
        # made copied configs with many stale entries freeze the GUI on exit.
        deadline = time.monotonic() + 1.5
        for t in threads:
            remaining_ms = max(0, int((deadline - time.monotonic()) * 1000))
            if remaining_ms <= 0:
                break
            t.wait(remaining_ms)
        event.accept()

    @staticmethod
    def _shutdown_http_server(server):
        try:
            server.shutdown()
        finally:
            server.server_close()


# ═══════════════════════════════════════════════════════════════════════
#  Helpers
# ═══════════════════════════════════════════════════════════════════════

def _safe_float(value) -> float:
    try:
        if isinstance(value, (int, float)):
            return float(value)
        return float(str(value).strip().replace(",", ""))
    except (TypeError, ValueError):
        return 0.0


def _human_size_to_gib(value: str) -> float:
    """Convert a `df -h` size such as 512M, 100G or 1.8T to GiB."""
    text = str(value).strip().upper().replace(",", "")
    match = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)([KMGTPE]?)(?:I?B)?", text)
    if not match:
        return 0.0
    number = float(match.group(1))
    unit = match.group(2)
    factors = {
        "": 1 / (1024 ** 3),
        "K": 1 / (1024 ** 2),
        "M": 1 / 1024,
        "G": 1.0,
        "T": 1024.0,
        "P": 1024.0 ** 2,
        "E": 1024.0 ** 3,
    }
    return number * factors[unit]


def _clamp(value, minimum, maximum):
    if maximum < minimum:
        maximum = minimum
    return max(minimum, min(maximum, value))


def _clear_layout(layout):
    while layout.count():
        item = layout.takeAt(0)
        widget = item.widget()
        child_layout = item.layout()
        if widget is not None:
            widget.deleteLater()
        elif child_layout is not None:
            _clear_layout(child_layout)
            child_layout.deleteLater()


def _update_task_table(table, tasks, note_matcher, queued=False, previous=None):
    signature = tuple(
        (
            str(t.get("pid", "")), str(t.get("user", "")),
            round(_safe_float(t.get("cpu")), 1),
            round(_safe_float(t.get("mem")), 1),
            str(t.get("gpu", "—")), str(t.get("state", "")),
            str(t.get("cmd", "")), str(t.get("etime", "")),
        )
        for t in tasks
    )
    if signature == previous:
        return previous

    table.setUpdatesEnabled(False)
    try:
        table.setRowCount(len(tasks))
        for row, task in enumerate(tasks):
            command = str(task.get("cmd", ""))
            note = note_matcher(command)
            state = str(task.get("state", "Queued")) if queued else task.get("gpu", "—")
            values = [
                task.get("pid", ""), task.get("user", ""),
                f"{_safe_float(task.get('cpu')):.1f}",
                f"{_safe_float(task.get('mem')):.1f}",
                state, command[:75], task.get("etime", ""), note,
            ]
            for column, text in enumerate(values):
                item = QTableWidgetItem(str(text))
                if column == 5:
                    item.setToolTip(command)
                if column == 2:
                    cpu = _safe_float(text)
                    item.setForeground(QColor(
                        C.RED if cpu > 80 else C.YELLOW if cpu > 50 else C.TEXT
                    ))
                if not queued and task.get("gpu") == "●" and column == 4:
                    item.setForeground(QColor(C.GREEN))
                    item.setText("● GPU")
                if queued and column == 4:
                    item.setForeground(QColor(C.YELLOW))
                if column == 7 and note:
                    item.setForeground(QColor(C.ACCENT))
                if column in (0, 2, 3, 4, 6):
                    item.setTextAlignment(Qt.AlignCenter)
                table.setItem(row, column, item)
    finally:
        table.setUpdatesEnabled(True)
    return signature


def _style_table(table: QTableWidget):
    table.setToolTip("Double-click a row to copy PID, command and all fields")
    table.setStyleSheet(f"""
        QTableWidget {{
            background: {C.CARD};
            color: {C.TEXT};
            border: 1px solid {C.BORDER};
            border-radius: 10px;
            gridline-color: {C.BORDER};
            font-size: 13px;
        }}
        QTableWidget::item {{
            padding: 9px 12px;
            color: {C.TEXT};
        }}
        QToolTip {{
            background-color: {C.SIDEBAR};
            color: #ffffff;
            border: 1px solid {C.BORDER};
            padding: 7px 9px;
            font-size: 12px;
        }}
        QTableWidget::item:alternate {{
            background: {C.CARD_HOVER};
        }}
        QTableWidget::item:selected {{
            background: {C.CARD_SELECTED};
        }}
        QHeaderView::section {{
            background: {C.SIDEBAR};
            color: {C.DIM};
            padding: 10px 12px;
            border: none;
            border-bottom: 1px solid {C.BORDER};
            font-weight: 600;
            font-size: 12px;
            text-transform: uppercase;
        }}
        QScrollBar:vertical {{
            background: {C.CARD};
            width: 10px;
            border-radius: 5px;
        }}
        QScrollBar::handle:vertical {{
            background: {C.BORDER};
            border-radius: 5px;
            min-height: 24px;
        }}
        QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{
            height: 0;
        }}
    """)
    table.setShowGrid(False)


# ═══════════════════════════════════════════════════════════════════════
#  Global stylesheet
# ═══════════════════════════════════════════════════════════════════════

_GLOBAL_CSS = f"""
    QMainWindow {{ background: {C.BG}; }}
    QScrollArea {{ border: none; }}
    QScrollBar:vertical {{
        background: {C.BG}; width: 12px; border-radius: 6px;
    }}
    QScrollBar::handle:vertical {{
        background: {C.BORDER}; border-radius: 6px; min-height: 28px;
    }}
    QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{
        height: 0;
    }}
    QScrollBar:horizontal {{
        background: {C.BG}; height: 12px; border-radius: 6px;
    }}
    QScrollBar::handle:horizontal {{
        background: {C.BORDER}; border-radius: 6px; min-width: 28px;
    }}
    QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal {{
        width: 0;
    }}
    QLabel {{ font-family: "Segoe UI", "SF Pro Display", system-ui, sans-serif; }}
"""


# ═══════════════════════════════════════════════════════════════════════
#  Entry point
# ═══════════════════════════════════════════════════════════════════════

def main():
    ap = argparse.ArgumentParser(description="SSH GPU Monitor Dashboard")
    ap.add_argument("-c", "--config", default="config.json",
                    help="Path to server config JSON (default: config.json)")
    ap.add_argument("--demo", action="store_true",
                    help="Run with simulated mock data (no SSH needed)")
    ap.add_argument("--api-port", type=int, default=8766,
                    help="Local API port for the Kyant frontend (default: 8766)")
    ap.add_argument("--backend-only", action="store_true",
                    help="Run the copied monitor as a hidden data backend")
    args = ap.parse_args()

    # ── HiDPI scaling (must be set BEFORE QApplication) ───────────────
    QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
    QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)
    try:
        QApplication.setHighDpiScaleFactorRoundingPolicy(
            Qt.HighDpiScaleFactorRoundingPolicy.PassThrough
        )
    except AttributeError:
        pass  # older PyQt5 versions

    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    if args.backend_only:
        # Keep the hidden API backend alive even though no dashboard window is shown.
        app.setQuitOnLastWindowClosed(False)

    # bump base font for HiDPI readability
    base_font = QFont("Segoe UI", 10)
    app.setFont(base_font)

    app.setStyleSheet(_GLOBAL_CSS)

    win = Dashboard(args.config, demo=args.demo, api_port=args.api_port)
    if not args.backend_only:
        win.show()

    try:
        sys.exit(app.exec_())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
