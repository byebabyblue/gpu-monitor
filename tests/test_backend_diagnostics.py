import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class BackendDiagnosticsTest(unittest.TestCase):
    def test_windowed_exceptions_and_threads_are_logged_before_imports(self):
        with tempfile.TemporaryDirectory(prefix="xerver backend logs ") as directory:
            config = Path(directory) / "config.json"
            script = f"""
import sys, threading
sys.argv = ['backend.exe', '--config', {str(config)!r}]
sys.stdout = sys.stderr = None
from backend_diagnostics import install_backend_diagnostics
install_backend_diagnostics()
install_backend_diagnostics()
print('windowed-stdout-is-recorded')
def fail_thread():
    raise RuntimeError('thread-probe-error')
thread = threading.Thread(target=fail_thread, name='diagnostic-probe')
thread.start()
thread.join()
raise ImportError('simulated-early-dependency-error')
"""
            result = subprocess.run([sys.executable, "-c", script], cwd=ROOT, capture_output=True, timeout=15)
            self.assertNotEqual(result.returncode, 0)
            log = (config.parent / "logs" / "backend.log").read_text(encoding="utf-8")
            self.assertEqual(log.count("Backend process started"), 1)
            self.assertIn("windowed-stdout-is-recorded", log)
            self.assertIn("thread-probe-error", log)
            self.assertIn("ImportError: simulated-early-dependency-error", log)

    def test_blocked_config_directory_uses_temp_fallback(self):
        with tempfile.TemporaryDirectory(prefix="xerver fallback ") as directory:
            root = Path(directory)
            blocked = root / "blocked"
            blocked.write_text("not a directory", encoding="utf-8")
            fallback = root / "fallback"
            fallback.mkdir()
            config = blocked / "config.json"
            script = f"""
import sys
sys.argv = ['backend.exe', '--config=' + {str(config)!r}]
from backend_diagnostics import install_backend_diagnostics, logger
install_backend_diagnostics()
logger.info('fallback-probe-ready')
"""
            environment = dict(os.environ, TMP=str(fallback), TEMP=str(fallback), TMPDIR=str(fallback))
            result = subprocess.run(
                [sys.executable, "-c", script], cwd=ROOT, env=environment, capture_output=True, timeout=15,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            log = (fallback / "Xerver" / "logs" / "backend.log").read_text(encoding="utf-8")
            self.assertIn("fallback-probe-ready", log)


if __name__ == "__main__":
    unittest.main()
