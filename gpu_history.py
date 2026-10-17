"""Stable, bounded 24-hour GPU history shared by the collector and API."""
import math

RANGE_BUCKET_SECONDS = {15: 2, 60: 5, 360: 30, 1440: 120}


def _aggregate(samples, width):
    buckets = {}
    for point in samples:
        key = math.floor(point['t'] / width)
        bucket = buckets.setdefault(key, {'t': point['t'], 'v': 0., 'n': 0, '_width': width,
                                           'memory': 0., 'memory_n': 0})
        count = point.get('n', 1)
        bucket['v'] += point['v'] * count
        bucket['n'] += count
        bucket['t'] = max(bucket['t'], point['t'])
        if point.get('memory') is not None:
            memory_count = point.get('memory_n', count)
            bucket['memory'] += point['memory'] * memory_count
            bucket['memory_n'] += memory_count
    result = []
    for key in sorted(buckets):
        bucket = buckets[key]
        bucket['v'] /= bucket['n']
        bucket['memory'] = (bucket['memory'] / bucket['memory_n']
                            if bucket['memory_n'] else None)
        result.append(bucket)
    return result


def append_sample(history, timestamp, utilization, memory=None):
    if history and timestamp <= history[-1]['t']:
        return  # Clock corrections must not reorder or overwrite recorded samples.
    history.append({'t': timestamp, 'v': utilization, 'memory': memory})
    cutoff = timestamp - 86400
    while history and history[0]['t'] < cutoff:
        history.pop(0)
    if len(history) < 2 or timestamp // 30 == history[-2]['t'] // 30:
        return
    # Match the precision of EVERY visible range. A five-second 1h bucket
    # must never be replaced by a minute average while it is still visible.
    raw_start = math.floor((timestamp - 3600) / 30) * 30
    medium_start = math.floor((timestamp - 21600) / 120) * 120
    old, medium, recent = [], [], []
    for point in history:
        (old if point['t'] < medium_start else
         medium if point['t'] < raw_start else recent).append(point)
    if any(p.get('_width') != 120 for p in old):
        old = _aggregate(old, 120)
    if any(p.get('_width') != 30 for p in medium):
        medium = _aggregate(medium, 30)
    history[:] = old + medium + recent


def history_points(history, range_minutes=1440):
    width = RANGE_BUCKET_SECONDS[range_minutes]
    history = list(history)
    if not history:
        return []
    cutoff = max(p['t'] for p in history) - range_minutes * 60
    history = [p for p in history if p['t'] >= cutoff]
    # Bucket boundaries are anchored to the epoch, never to the sample count
    # or the moving start of the chart. Completed buckets cannot change.
    return [{'timestamp': p['t'], 'value': p['v'], 'memory_percent': p['memory']}
            for p in _aggregate(list(history), width)]
