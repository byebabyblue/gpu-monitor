import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

class HeadlessBackendTest(unittest.TestCase):
    def test_core_backend_never_constructs_legacy_widgets_or_loads_charts(self):
        script = r'''
import sys, tempfile
from pathlib import Path
from unittest.mock import patch
from PyQt5.QtCore import QCoreApplication
from PyQt5.QtWidgets import QWidget
from gpu_dashboard_kyant import MonitorBackend, MonitorState, Dashboard
app = QCoreApplication([])
with tempfile.TemporaryDirectory() as directory:
    config = Path(directory) / 'config.json'
    config.write_text('{"servers": []}')
    with patch.object(MonitorState, '_start_api'), patch.object(MonitorState, '_start_threads'), patch.object(Dashboard, '_build_ui', side_effect=AssertionError('legacy UI created')):
        backend = MonitorBackend(str(config), demo=True)
        assert len(backend._threads) == 4
        assert not isinstance(backend, QWidget)
        assert not backend._cards
        assert not hasattr(backend, '_ctimer')
        assert not hasattr(backend, '_detail_views')
        assert 'pyqtgraph' not in sys.modules
        assert 'numpy' not in sys.modules
        backend._stop_backend()
        backend._stop_backend()
'''
        result = subprocess.run([sys.executable, '-c', script], cwd=ROOT, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_core_event_loop_handles_settings_add_remove_and_network_requests(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({'servers': [], 'ui': {'autostart': False}}), encoding='utf-8')
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0)); port = listener.getsockname()[1]
            process = subprocess.Popen([sys.executable, str(ROOT / 'gpu_dashboard_kyant.py'), '--backend-only', '--config', str(config), '--api-port', str(port)], cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            def request(path, body=None, method=None):
                req = urllib.request.Request(f'http://127.0.0.1:{port}'+path,
                    data=None if body is None else json.dumps(body).encode(),
                    headers={'Content-Type': 'application/json'}, method=method)
                with opener.open(req, timeout=8) as response: return json.load(response)
            try:
                for _ in range(80):
                    try:
                        request('/api/config'); break
                    except OSError: time.sleep(.1)
                else: self.fail('Core API failed to start')
                saved = request('/api/settings', {'settings': {'network_interval_hours': 168}, 'ui': {'font_scale': 1.35}})
                self.assertTrue(saved['ok'])
                self.assertEqual(168, json.loads(config.read_text(encoding='utf-8'))['settings']['network_interval_hours'])
                added = request('/api/servers', {'name': 'local-test', 'host': '127.0.0.1', 'port': 1, 'user': 'fixture'})
                self.assertTrue(added['ok'])
                self.assertIn('local-test', request('/api/servers'))
                self.assertTrue(request('/api/network/refresh', {})['ok'])
                self.assertEqual([], request('/api/host-key-prompts')['prompts'])
                self.assertTrue(request('/api/servers/local-test', {}, 'DELETE')['ok'])
                self.assertEqual({}, request('/api/servers'))
                self.assertEqual([], json.loads(config.read_text(encoding='utf-8'))['servers'])
            finally:
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                else: process.terminate()
                process.wait(timeout=10)
