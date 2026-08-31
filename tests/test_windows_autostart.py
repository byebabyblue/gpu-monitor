from contextlib import nullcontext
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import windows_autostart


class MemoryRegistry:
    HKEY_CURRENT_USER = 1
    KEY_QUERY_VALUE = 1
    KEY_SET_VALUE = 2
    REG_SZ = 1

    def __init__(self, values=None, key_exists=True):
        self.values = dict(values or {})
        self.key_exists = key_exists
        self.writes = []
        self.OpenKey = Mock(side_effect=self._open)
        self.CreateKeyEx = Mock(side_effect=self._create)
        self.QueryValueEx = Mock(side_effect=self._query)
        self.SetValueEx = Mock(side_effect=self._set)
        self.DeleteValue = Mock(side_effect=self._delete)

    def _open(self, root, name, reserved, access):
        assert root == self.HKEY_CURRENT_USER and name == windows_autostart.RUN_KEY
        if not self.key_exists:
            raise FileNotFoundError(name)
        return nullcontext("memory key")

    def _create(self, root, name, reserved, access):
        assert root == self.HKEY_CURRENT_USER and name == windows_autostart.RUN_KEY
        self.key_exists = True
        return nullcontext("memory key")

    def _query(self, key, name):
        if name not in self.values:
            raise FileNotFoundError(name)
        return self.values[name]

    def _set(self, key, name, reserved, value_type, value):
        self.values[name] = (value, value_type)
        self.writes.append(("set", name, value))

    def _delete(self, key, name):
        if name not in self.values:
            raise FileNotFoundError(name)
        del self.values[name]
        self.writes.append(("delete", name))


class WindowsAutostartTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="xerver autostart 测试 ")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.config = self.root / "config.json"
        self.frontend = self.root / "Xerver.exe"
        self.frontend.write_text("test fixture, never executed", encoding="utf-8")
        self.command = f'"{self.frontend.resolve()}"'

    def update(self, registry, enabled, frontend=None):
        with patch.dict(sys.modules, {"winreg": registry}), patch.object(windows_autostart.sys, "platform", "win32"):
            return windows_autostart.update_autostart(
                enabled, self.config, str(self.frontend if frontend is None else frontend),
            )

    def test_matching_entry_is_read_only(self):
        registry = MemoryRegistry({"Xerver": (self.command, 1)})
        self.assertFalse(self.update(registry, True))
        self.assertEqual(registry.writes, [])
        registry.CreateKeyEx.assert_not_called()
        self.assertTrue(all(call.args[3] == registry.KEY_QUERY_VALUE for call in registry.OpenKey.call_args_list))

    def test_enabling_writes_once_and_repeating_does_not(self):
        registry = MemoryRegistry()
        self.assertTrue(self.update(registry, True))
        self.assertFalse(self.update(registry, True))
        self.assertEqual(registry.writes, [("set", "Xerver", self.command)])

    def test_disabled_missing_entry_never_opens_for_write(self):
        registry = MemoryRegistry(key_exists=False)
        self.assertFalse(self.update(registry, False, frontend="missing.exe"))
        self.assertEqual(registry.writes, [])
        registry.CreateKeyEx.assert_not_called()

    def test_enabling_creates_a_missing_run_key(self):
        registry = MemoryRegistry(key_exists=False)
        self.assertTrue(self.update(registry, True))
        registry.CreateKeyEx.assert_called_once()
        self.assertEqual(registry.values["Xerver"], (self.command, 1))

    def test_disable_removes_only_xerver_and_its_legacy_entry(self):
        registry = MemoryRegistry({"Xerver": (self.command, 1), "GPU Monitor": ("legacy", 1), "OtherApp": ("other", 1)})
        self.assertTrue(self.update(registry, False))
        self.assertFalse(self.update(registry, False))
        self.assertEqual(registry.values, {"OtherApp": ("other", 1)})
        self.assertEqual(registry.writes, [("delete", "Xerver"), ("delete", "GPU Monitor")])

    def test_legacy_cleanup_does_not_rewrite_correct_entry(self):
        registry = MemoryRegistry({"Xerver": (self.command, 1), "GPU Monitor": ("legacy", 1)})
        self.assertTrue(self.update(registry, True))
        self.assertEqual(registry.writes, [("delete", "GPU Monitor")])
        registry.CreateKeyEx.assert_not_called()

    def test_packaged_frontend_takes_precedence_over_legacy_script(self):
        (self.root / "start_gpu_monitor.ps1").write_text("# fixture", encoding="utf-8")
        registry = MemoryRegistry()
        self.update(registry, True)
        self.assertEqual(registry.values["Xerver"], (self.command, 1))
        self.assertNotIn("powershell", registry.values["Xerver"][0].lower())

    def test_source_installation_keeps_explicit_script_fallback(self):
        script = self.root / "start_gpu_monitor.ps1"
        script.write_text("# fixture", encoding="utf-8")
        registry = MemoryRegistry()
        self.update(registry, True, frontend="")
        self.assertEqual(registry.values["Xerver"], (
            f'powershell.exe -NoLogo -NoProfile -WindowStyle Hidden -File "{script}"', 1,
        ))

    def test_missing_launcher_fails_without_touching_registry(self):
        registry = MemoryRegistry()
        with self.assertRaisesRegex(ValueError, "launcher was not found"):
            self.update(registry, True, frontend="missing.exe")
        registry.OpenKey.assert_not_called()
        self.assertEqual(registry.writes, [])

    def test_non_windows_does_not_import_or_touch_registry(self):
        registry = MemoryRegistry()
        with patch.dict(sys.modules, {"winreg": registry}), patch.object(windows_autostart.sys, "platform", "linux"):
            self.assertFalse(windows_autostart.update_autostart(True, self.config, str(self.frontend)))
        registry.OpenKey.assert_not_called()


if __name__ == "__main__":
    unittest.main()
