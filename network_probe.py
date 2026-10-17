"""Fixed-target, bounded remote network probes. No user-supplied shell input."""
import math
import shlex

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
