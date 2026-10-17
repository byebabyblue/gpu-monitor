import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from unittest.mock import Mock, patch

from gpu_dashboard_kyant import DataCollector, MonitorThread, ServerInfo, SSHConnection

ROOT = Path(__file__).resolve().parents[1]


def gpu_output(indices):
    return '\n'.join(f'{i}, Test GPU, 75, 1024, 8192, 60, 100, 200, GPU-{i}' for i in indices) + '\n__GPU_STATUS__0\n__GPU_PIDS__\n'


class CollectorRegressionTest(unittest.TestCase):
    def test_failed_authentication_explicitly_closes_the_temporary_client(self):
        with patch('gpu_dashboard_kyant.paramiko.SSHClient') as factory:
            factory.return_value.connect.side_effect = RuntimeError('authentication failed')
            ssh = SSHConnection('example.test', 22, 'user')
            with self.assertRaises(RuntimeError):
                ssh.connect()
            factory.return_value.close.assert_called_once()
            self.assertIsNone(ssh._client)

    def test_failed_gpu_query_preserves_current_devices_and_history(self):
        ssh = Mock()
        ssh.exec.return_value = gpu_output([0, 1])
        collector, info = DataCollector(ssh), ServerInfo('test')
        collector._gpu(info)
        before = json.dumps(info.gpu_history)
        ssh.exec.return_value = '__GPU_STATUS__1\n__GPU_PIDS__\n'
        with self.assertRaises(RuntimeError):
            collector._gpu(info)
        self.assertEqual([0, 1], [g['idx'] for g in info.gpus])
        self.assertEqual(before, json.dumps(info.gpu_history))

    def test_history_follows_device_identity_through_missing_gpu_and_reordering(self):
        ssh = Mock()
        collector, info = DataCollector(ssh), ServerInfo('test')
        ssh.exec.return_value = gpu_output([0, 1])
        collector._gpu(info)
        first = info.gpu_history[1][0]
        ssh.exec.return_value = gpu_output([1])
        collector._gpu(info)
        self.assertEqual(first, info.gpu_history[0][0])
        ssh.exec.return_value = gpu_output([1, 0])
        collector._gpu(info)
        self.assertEqual(first, info.gpu_history[0][0])
        self.assertEqual(0, info.gpus[1]['idx'])

    def test_network_results_publish_during_idle_gpu_sleep(self):
        thread = MonitorThread({'name': 'test'})
        thread.info.connected = True
        thread.collector = Mock()
        thread.collector._refresh_network.side_effect = lambda info: setattr(info, 'network_checked_at', 123)
        thread._emit_snapshot = Mock()
        thread._sleep(0.01)
        thread.collector._refresh_network.assert_called_once()
        thread._emit_snapshot.assert_called_once()

    def test_probe_transport_is_pinned_to_the_verified_host(self):
        ssh = SSHConnection('example.test', 2222, 'user')
        ssh._client = Mock()
        transport = ssh._client.get_transport.return_value
        transport.is_active.return_value = True
        key = transport.get_remote_server_key.return_value
        clone = ssh.probe_connection()
        self.assertIs(key, clone.accepted_host_key)
        self.assertEqual('[example.test]:2222', clone.accepted_host_key_name)
        self.assertFalse(clone.remember_host_key)
        self.assertIsNone(clone.known_hosts_path)


class ApiIntegrationTest(unittest.TestCase):
    def test_native_api_works_but_web_origins_and_rebinding_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0))
                port = listener.getsockname()[1]
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({'servers': []}), encoding='utf-8')
            env = os.environ.copy()
            env['GPU_MONITOR_CONFIG_PATH'] = str(config)
            env['QT_QPA_PLATFORM'] = 'offscreen'
            process = subprocess.Popen([sys.executable, str(ROOT / 'gpu_dashboard_kyant.py'),
                                        '--backend-only', '--demo', '--api-port', str(port),
                                        '--config', str(config)], env=env,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                base = f'http://127.0.0.1:{port}'
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                for _ in range(100):
                    try:
                        with opener.open(base + '/api/servers', timeout=1) as response:
                            servers = json.load(response)
                        if servers and all(s['gpus'] for s in servers.values()):
                            break
                    except (OSError, ValueError):
                        pass
                    time.sleep(.1)
                else:
                    self.fail('Demo API did not become ready')
                name = urllib.parse.quote(next(iter(servers)), safe='')
                with opener.open(base + f'/api/servers/{name}/history?range_minutes=60') as response:
                    data = json.load(response)
                    self.assertNotIn('Access-Control-Allow-Origin', response.headers)
                    self.assertIn('memory_percent', data['series'][0]['points'][0])
                for method in ('GET', 'POST', 'DELETE'):
                    request = urllib.request.Request(base + '/api/servers', method=method,
                        headers={'Origin': 'https://evil.example', 'Content-Type': 'application/json'},
                        data=b'{}' if method == 'POST' else None)
                    with self.assertRaises(urllib.error.HTTPError) as caught:
                        opener.open(request)
                    self.assertEqual(403, caught.exception.code)
                    caught.exception.close()
                request = urllib.request.Request(base + '/api/servers', headers={'Host': f'evil.example:{port}'})
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    opener.open(request)
                self.assertEqual(403, caught.exception.code)
                caught.exception.close()
                with self.assertRaises(urllib.error.HTTPError) as caught:
                    opener.open(base + f'/api/servers/{name}/history?range_minutes=7')
                self.assertEqual(400, caught.exception.code)
                caught.exception.close()
            finally:
                if os.name == 'nt':
                    # Windows venv python.exe may be a redirector with a real
                    # interpreter child; terminate only this test's full tree.
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                else:
                    process.terminate()
                process.wait(timeout=10)
