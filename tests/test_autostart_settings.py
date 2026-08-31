import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PyQt5.QtWidgets import QApplication
from gpu_dashboard_kyant import Dashboard, RefreshSettings


class AutostartSettingsTest(unittest.TestCase):
    def state(self, enabled):
        return SimpleNamespace(
            _cfg={"settings": {}, "ui": {"autostart": enabled}},
            _api_port=8766,
            _set_autostart=Mock(),
            _save_config=Mock(return_value=True),
            _api_config_dict=Mock(return_value={}),
        )

    def test_saving_other_settings_never_updates_startup(self):
        for enabled in (False, True):
            with self.subTest(enabled=enabled):
                state = self.state(enabled)
                Dashboard._api_update_settings(state, {"ui": {"autostart": enabled, "font_scale": 1.05}})
                state._set_autostart.assert_not_called()
                self.assertEqual(state._cfg["ui"]["font_scale"], 1.05)

    def test_payload_without_autostart_never_updates_startup(self):
        state = self.state(True)
        Dashboard._api_update_settings(state, {"ui": {"default_page": "gpu"}})
        state._set_autostart.assert_not_called()

    def test_enabling_then_saving_again_registers_only_once(self):
        state = self.state(False)
        Dashboard._api_update_settings(state, {"ui": {"autostart": True}})
        Dashboard._api_update_settings(state, {"ui": {"autostart": True, "font_scale": 1.1}})
        state._set_autostart.assert_called_once_with(True)
        self.assertTrue(state._cfg["ui"]["autostart"])

    def test_disabling_unregisters_once(self):
        state = self.state(True)
        Dashboard._api_update_settings(state, {"ui": {"autostart": False}})
        Dashboard._api_update_settings(state, {"ui": {"autostart": False}})
        state._set_autostart.assert_called_once_with(False)

    def test_invalid_settings_do_not_change_startup_or_toggle(self):
        state = self.state(False)
        with self.assertRaises(ValueError):
            Dashboard._api_update_settings(state, {"ui": {"autostart": True, "font_scale": 999}})
        state._set_autostart.assert_not_called()
        state._save_config.assert_not_called()
        self.assertFalse(state._cfg["ui"]["autostart"])

    def test_registry_error_does_not_commit_toggle_and_can_be_retried(self):
        state = self.state(False)
        state._set_autostart.side_effect = PermissionError("fixture")
        with self.assertRaises(PermissionError):
            Dashboard._api_update_settings(state, {"ui": {"autostart": True}})
        state._save_config.assert_not_called()
        self.assertFalse(state._cfg["ui"]["autostart"])
        state._set_autostart.side_effect = None
        Dashboard._api_update_settings(state, {"ui": {"autostart": True}})
        self.assertEqual(state._set_autostart.call_count, 2)

    def test_real_dashboard_startup_with_autostart_enabled_never_registers(self):
        app = QApplication.instance() or QApplication([])
        with tempfile.TemporaryDirectory(prefix="xerver startup preferences ") as directory:
            config = Path(directory) / "config.json"
            config.write_text(json.dumps({"servers": [], "ui": {"autostart": True}}), encoding="utf-8")
            with patch.object(Dashboard, "_set_autostart") as register, \
                 patch.object(Dashboard, "_start_api"), patch.object(Dashboard, "_start_threads"), \
                 patch.object(RefreshSettings, "_config_path", None):
                dashboard = Dashboard(str(config))
                try:
                    self.assertTrue(dashboard._cfg["ui"]["autostart"])
                    register.assert_not_called()
                finally:
                    dashboard.close()
                    dashboard.deleteLater()
                    app.processEvents()


if __name__ == "__main__":
    unittest.main()
