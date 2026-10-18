"""Fixed-target, bounded remote network probes. No user-supplied shell input."""
import math
import shlex
import copy
import hashlib
import ipaddress
import json
import threading
import time
from pathlib import Path


class NetworkProbeCache:
    """Share one result per server address and pace probes to the same host."""

    def __init__(self):
        self._lock = threading.Lock()
        self._entries = {}
        self._endpoint_locks = {}
        self._host_locks = {}
        self._host_last = {}
        self._path = None

    def configure(self, path):
        with self._lock:
            self._path = Path(path) if path else None
            self._entries = {}
            if self._path:
                try:
                    entries = json.loads(self._path.read_text(encoding='utf-8'))
                    if isinstance(entries, dict):
                        for key, result in entries.items():
                            if (isinstance(result, list) and len(result) == 3
                                    and isinstance(result[0], list) and all(isinstance(row, dict) for row in result[0])
                                    and isinstance(result[1], (int, float))
                                    and math.isfinite(result[1])
                                    and 0 < result[1] <= time.time() + 60
                                    and isinstance(result[2], str)):
                                self._entries[key] = tuple(result)
                except (OSError, ValueError, TypeError):
                    pass

    @staticmethod
    def identity(ssh):
        # Connectivity is explicitly shared by address, including different SSH ports.
        host = str(ssh.host).strip().lower().strip("[]").rstrip(".")
        try:
            host = ipaddress.ip_address(host).compressed
        except ValueError:
            pass
        key = hashlib.sha256(("address-v1:" + host).encode()).hexdigest()
        return key, host

    def cached(self, key):
        with self._lock:
            return copy.deepcopy(self._entries.get(key, ([], None, '')))

    def run(self, key, host, interval_seconds, requested_at, force, stopped, operation):
        with self._lock:
            endpoint_lock = self._endpoint_locks.setdefault(key, threading.Lock())
            host_lock = self._host_locks.setdefault(host, threading.Lock())
        with endpoint_lock:
            result = self.cached(key)
            checked_at = result[1] or 0
            if checked_at and ((not force and time.time() < checked_at + interval_seconds)
                               or checked_at >= requested_at):
                return result
            with host_lock:
                with self._lock:
                    delay = max(0, self._host_last.get(host, 0) + 3 - time.monotonic())
                if stopped.wait(delay):
                    return result
                with self._lock:
                    self._host_last[host] = time.monotonic()
                if stopped.is_set():
                    return result
                result = operation()
            if stopped.is_set():
                return result
            with self._lock:
                self._entries[key] = copy.deepcopy(result)
                if self._path:
                    try:
                        self._path.parent.mkdir(parents=True, exist_ok=True)
                        temporary = self._path.with_suffix('.tmp')
                        temporary.write_text(json.dumps(self._entries, ensure_ascii=False), encoding='utf-8')
                        temporary.replace(self._path)
                    except OSError:
                        pass
            return result


NETWORK_PROBES = NetworkProbeCache()

TARGETS = (
    ('baidu', '百度', 'www.baidu.com', 'https://www.baidu.com/'),
    ('github', 'GitHub', 'github.com', 'https://github.com/'),
    ('mirror', '清华 PyPI 源', 'pypi.tuna.tsinghua.edu.cn', 'https://pypi.tuna.tsinghua.edu.cn/simple/'),
    ('pytorch', 'PyTorch 官方源', 'download.pytorch.org', 'https://download.pytorch.org/whl/'),
)


def probe_command():
    commands = []
    for key, _, host, url in TARGETS:
        commands.append(r'''(
ping_ms=unavailable
if command -v ping >/dev/null 2>&1; then
  ping_ms=$(LC_ALL=C ping -n -c 1 -W 2 -w 3 HOST 2>/dev/null | sed -n 's/.*time[=<]\([0-9.]*\).*/\1/p' | head -n 1)
  test -n "$ping_ms" || ping_ms=timeout
fi
https=unavailable
if command -v curl >/dev/null 2>&1; then
  https=$(curl -L -s -o /dev/null --connect-timeout 2 --max-time 4 -w '%{http_code} %{time_total}' URL 2>/dev/null)
  test -n "$https" || https='000 0'
fi
printf 'KEY\t%s\t%s\n' "$ping_ms" "$https"
) &'''.replace('HOST', shlex.quote(host)).replace('URL', shlex.quote(url)).replace('KEY', key))
    return '\n'.join(commands) + '\nwait\n'


def parse_probe(output):
    rows = {}
    for line in output.splitlines():
        parts = line.split('\t')
        if len(parts) == 3:
            rows[parts[0]] = parts[1:]
    results = []
    for key, label, host, url in TARGETS:
        ping, https = rows.get(key, ['unavailable', 'unavailable'])
        try:
            ping_ms = float(ping)
            if not math.isfinite(ping_ms) or ping_ms < 0:
                raise ValueError()
            ping_status = 'ok'
        except ValueError:
            ping_ms = None
            ping_status = 'timeout' if ping == 'timeout' else 'unavailable'
        code, _, seconds = https.partition(' ')
        try:
            code = int(code)
            seconds = float(seconds)
            if not math.isfinite(seconds) or seconds < 0:
                raise ValueError()
            status = 'ok' if 200 <= code < 400 else 'http_error' if code else 'failed'
            https_ms = round(seconds * 1000, 1) if code else None
        except ValueError:
            code, https_ms, status = None, None, 'unavailable'
        results.append(dict(id=key, label=label, host=host, url=url,
                            ping_ms=ping_ms, ping_status=ping_status,
                            https_ms=https_ms, https_status=status, http_code=code))
    return results
