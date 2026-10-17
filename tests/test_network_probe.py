import threading
import unittest
from unittest.mock import Mock
from network_probe import TARGETS, parse_probe, probe_command
from gpu_dashboard_kyant import DataCollector, ServerInfo


class NetworkProbeTest(unittest.TestCase):
    def test_ping_and_https_are_independent(self):
        results = parse_probe('baidu\ttimeout\t200 0.123\ngithub\t10.5\t403 0.25\nmirror\tunavailable\tunavailable\npytorch\t8\t000 4.0')
        self.assertEqual('timeout', results[0]['ping_status'])
        self.assertEqual('ok', results[0]['https_status'])
        self.assertEqual(123, results[0]['https_ms'])
        self.assertEqual('ok', results[1]['ping_status'])
        self.assertEqual('http_error', results[1]['https_status'])
        self.assertEqual('unavailable', results[2]['https_status'])
        self.assertEqual('failed', results[3]['https_status'])

    def test_invalid_and_missing_values_are_not_successes(self):
        results = parse_probe('baidu\tNaN\t200 nan\nunknown\t4\t200 0.1')
        self.assertEqual(4, len(results))
        self.assertTrue(all(p['ping_ms'] is None and p['https_ms'] is None for p in results))

    def test_command_has_fixed_hosts_and_time_limits(self):
        command = probe_command()
        self.assertEqual(4, command.count('--max-time 4'))
        self.assertEqual(4, command.count(') &'))
        self.assertTrue(command.endswith('wait\n'))
        for _, _, host, url in TARGETS:
            self.assertIn(host, command)
            self.assertIn(url, command)
        self.assertNotIn('-k ', command)

    def test_background_probe_does_not_block_collection_or_flood_ssh(self):
        release = threading.Event()
        ssh = Mock()
        probe_ssh = ssh.probe_connection.return_value
        probe_ssh.exec.side_effect = lambda *a, **kw: (release.wait(2), 'baidu\t1\t200 0.01')[1]
        collector = DataCollector(ssh)
        info = ServerInfo('test')
        try:
            collector._refresh_network(info)
            collector._refresh_network(info)
            self.assertTrue(collector._network_worker.is_alive())
        finally:
            release.set()
            collector._network_worker.join(3)
        collector._refresh_network(info)
        self.assertEqual(1, probe_ssh.exec.call_count)
        self.assertEqual(0, ssh.exec.call_count)
        probe_ssh.disconnect.assert_called_once()
        self.assertEqual('ok', info.network[0]['https_status'])

    def test_probe_failure_does_not_claim_success(self):
        ssh = Mock()
        ssh.probe_connection.return_value.exec.side_effect = TimeoutError()
        collector = DataCollector(ssh)
        info = ServerInfo('test')
        collector._refresh_network(info)
        collector._network_worker.join(3)
        collector._refresh_network(info)
        self.assertEqual([], info.network)
        self.assertIn('失败', info.network_error)
