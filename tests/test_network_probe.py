import threading
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock
from network_probe import TARGETS, parse_probe, probe_command, NetworkProbeCache
from gpu_dashboard_kyant import DataCollector, ServerInfo


class NetworkProbeTest(unittest.TestCase):
    def test_cache_coalesces_concurrent_and_manual_checks_and_survives_restart(self):
        cache = NetworkProbeCache()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'network-cache.json'
            cache.configure(path)
            stopped = threading.Event()
            entered = threading.Event()
            release = threading.Event()
            requested_at = time.time()
            def operation():
                entered.set()
                release.wait(2)
                return ([{'ping_status': 'ok'}], time.time(), '')
            operation_mock = Mock(side_effect=operation)
            results = []
            def run(force):
                results.append(cache.run('key', 'host', 43200, requested_at, force, stopped, operation_mock))
            first = threading.Thread(target=run, args=(False,))
            second = threading.Thread(target=run, args=(True,))
            first.start()
            self.assertTrue(entered.wait(1))
            second.start()
            release.set()
            first.join(3)
            second.join(3)
            self.assertEqual(1, operation_mock.call_count)
            self.assertEqual(results[0], results[1])
            # A duplicate alias can consume the same click after the first job finished.
            cache.run('key', 'host', 43200, requested_at, True, stopped, operation_mock)
            self.assertEqual(1, operation_mock.call_count)
            restored = NetworkProbeCache()
            restored.configure(path)
            restored.run('key', 'host', 43200, time.time(), False, stopped, operation_mock)
            self.assertEqual(1, operation_mock.call_count)
            # A later explicit click is allowed even before the automatic interval.
            restored.run('key', 'host', 43200, time.time(), True, stopped, operation_mock)
            self.assertEqual(2, operation_mock.call_count)

    def test_failed_round_is_cached_too_and_does_not_trigger_alias_retries(self):
        cache = NetworkProbeCache()
        stopped = threading.Event()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'network-cache.json'
            cache.configure(path)
            requested = time.time()
            operation = Mock(return_value=([], time.time(), '网络检测失败'))
            cache.run('same-ip', '203.0.113.100', 43200, requested, False, stopped, operation)
            cache.run('same-ip', '203.0.113.100', 43200, requested, True, stopped, operation)
            self.assertEqual(1, operation.call_count)
            restored = NetworkProbeCache()
            restored.configure(path)
            restored.run('same-ip', '203.0.113.100', 43200, time.time(), False, stopped, operation)
            self.assertEqual(1, operation.call_count)

    def test_same_ip_shares_results_across_ports_users_and_host_keys(self):
        base = dict(host='203.0.113.1', port=22, user='a')
        key, host = NetworkProbeCache.identity(SimpleNamespace(**base))
        for change in ({'port': 2222}, {'user': 'b'}, {'key_file': '/other/key'}):
            self.assertEqual((key, host), NetworkProbeCache.identity(SimpleNamespace(**(base | change))))
        self.assertNotEqual(key, NetworkProbeCache.identity(SimpleNamespace(host='203.0.113.2'))[0])
        self.assertEqual(NetworkProbeCache.identity(SimpleNamespace(host='[2001:db8::1]')),
                         NetworkProbeCache.identity(SimpleNamespace(host='2001:0db8:0000::1')))

    def test_different_ssh_connections_on_one_ip_execute_only_one_probe(self):
        from network_probe import NETWORK_PROBES
        NETWORK_PROBES.configure(None)
        first = Mock(host='203.0.113.90', port=22, user='a')
        second = Mock(host='203.0.113.90', port=2222, user='b')
        first.probe_connection.return_value.exec.return_value = 'baidu\t1\t200 0.01'
        second.probe_connection.return_value.exec.return_value = 'baidu\t2\t200 0.02'
        collectors = [DataCollector(first), DataCollector(second)]
        requested = time.time()
        for collector in collectors:
            collector.request_network_refresh(requested)
        info = [ServerInfo('one'), ServerInfo('two')]
        collectors[0]._refresh_network(info[0])
        collectors[0]._network_worker.join(3)
        # Consume the second alias only after the first completed: still one click.
        collectors[1]._refresh_network(info[1])
        collectors[1]._network_worker.join(3)
        for collector, snapshot in zip(collectors, info): collector._refresh_network(snapshot)
        self.assertEqual(1, first.probe_connection.return_value.exec.call_count)
        self.assertEqual(0, second.probe_connection.call_count)
        self.assertEqual(info[0].network, info[1].network)
        NETWORK_PROBES.configure(None)

    def test_stopped_probe_never_opens_a_connection_or_caches_a_new_result(self):
        cache = NetworkProbeCache()
        stopped = threading.Event()
        stopped.set()
        operation = Mock()
        cache.run('key', 'host', 43200, time.time(), True, stopped, operation)
        operation.assert_not_called()
        self.assertIsNone(cache.cached('key')[1])

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
