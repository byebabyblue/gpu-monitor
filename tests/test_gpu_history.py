import unittest
from gpu_history import append_sample, history_points


class GpuHistoryTest(unittest.TestCase):
    def test_completed_buckets_do_not_change_when_new_samples_arrive(self):
        history = []
        for timestamp in range(0, 10800, 3):
            append_sample(history, timestamp, timestamp % 100, timestamp % 80)
        before = history_points(history)
        for timestamp in range(10800, 10830, 3):
            append_sample(history, timestamp, 99, 95)
        after = history_points(history)
        self.assertEqual(before, after[:len(before)])

    def test_repeated_compaction_keeps_weighted_mean_and_memory(self):
        history = []
        for timestamp in range(0, 4000, 3):
            append_sample(history, timestamp, timestamp % 100, timestamp % 80)
        first_minute = history[0]
        expected = list(range(0, 30, 3))
        self.assertAlmostEqual(sum(t % 100 for t in expected) / len(expected), first_minute['v'])
        self.assertAlmostEqual(sum(t % 80 for t in expected) / len(expected), first_minute['memory'])
        self.assertEqual(len(expected), first_minute['n'])

    def test_24_hour_retention_and_short_range_response_are_bounded(self):
        history = []
        for timestamp in range(0, 90000, 3):
            append_sample(history, timestamp, 60, 80)
        self.assertLessEqual(len(history), 2500)
        for minutes in (15, 60, 360, 1440):
            points = history_points(history, minutes)
            self.assertLessEqual(len(points), 722)
            self.assertTrue(all(p['value'] == 60 and p['memory_percent'] == 80 for p in points))

    def test_legacy_samples_do_not_fabricate_zero_memory(self):
        points = history_points([{'t': 10, 'v': 50}])
        self.assertIsNone(points[0]['memory_percent'])

    def test_each_visible_range_keeps_completed_buckets_across_storage_tiers(self):
        history = []
        for timestamp in range(0, 22500, 3):
            if timestamp in (3600, 3630, 21600, 21720):
                ranges = (15, 60, 360, 1440)
                snapshots = {minutes: history_points(history, minutes) for minutes in ranges}
                append_sample(history, timestamp, timestamp % 101, timestamp % 83)
                for minutes in (15, 60, 360, 1440):
                    before = snapshots[minutes]
                    after = history_points(history, minutes)
                    after_by_t = {p['timestamp']: p for p in after}
                    width = {15: 2, 60: 5, 360: 30, 1440: 120}[minutes]
                    for point in before:
                        # Ignore only the moving left edge and current bucket.
                        if timestamp - minutes * 60 + width <= point['timestamp'] < timestamp // width * width:
                            other = after_by_t[point['timestamp']]
                            self.assertAlmostEqual(point['value'], other['value'])
                            self.assertAlmostEqual(point['memory_percent'], other['memory_percent'])
            append_sample(history, timestamp, timestamp % 101, timestamp % 83)


if __name__ == '__main__':
    unittest.main()
