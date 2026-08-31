import ast
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SHELL = shutil.which("powershell.exe") or shutil.which("pwsh")
REQUIRED = (
    "gpu-monitor-backend.exe", "_internal/base_library.zip", "_internal/python313.dll",
    "_internal/_ctypes.pyd", "_internal/PyQt5/Qt5/bin/Qt5Core.dll",
    "_internal/PyQt5/Qt5/bin/Qt5Gui.dll", "_internal/PyQt5/Qt5/bin/Qt5Widgets.dll",
    "_internal/PyQt5/Qt5/plugins/platforms/qwindows.dll",
)


class BackendSpecTest(unittest.TestCase):
    def test_spec_collects_full_onedir_runtime_without_upx(self):
        tree = ast.parse((ROOT / "gpu-monitor-backend.spec").read_text(encoding="utf-8"))
        calls = {node.func.id: node for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)}
        exe = {item.arg: ast.literal_eval(item.value) for item in calls["EXE"].keywords}
        collect = {item.arg: ast.literal_eval(item.value) for item in calls["COLLECT"].keywords}
        self.assertTrue(exe["exclude_binaries"])
        self.assertEqual(exe["contents_directory"], "_internal")
        self.assertFalse(exe["upx"])
        self.assertFalse(collect["upx"])
        self.assertEqual([ast.unparse(item) for item in calls["COLLECT"].args], ["exe", "a.binaries", "a.datas"])


@unittest.skipUnless(SHELL, "PowerShell is required to test the Windows packaging verifier")
class BackendBundleTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="xerver bundle 完整性 ")
        self.addCleanup(self.directory.cleanup)
        self.bundle = Path(self.directory.name)
        for relative in REQUIRED:
            path = self.bundle / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("non-executable fixture: " + relative).encode("utf-8"))
        self.manifest = self.bundle / "backend-manifest.sha256"
        self.write_manifest()

    def write_manifest(self):
        lines = [
            f"{hashlib.sha256(path.read_bytes()).hexdigest()} *{path.relative_to(self.bundle).as_posix()}"
            for path in sorted(self.bundle.rglob("*")) if path.is_file() and path != self.manifest
        ]
        self.manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def verify(self):
        return subprocess.run(
            [SHELL, "-NoProfile", "-NonInteractive", "-File", str(ROOT / "tools/verify_backend_bundle.ps1"),
             "-BackendDirectory", str(self.bundle)],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20,
        )

    def test_complete_bundle_passes(self):
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_missing_file_fails(self):
        (self.bundle / "_internal/python313.dll").unlink()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_changed_file_fails(self):
        (self.bundle / "_internal/python313.dll").write_bytes(b"modified fixture")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("checksum mismatch", result.stderr)

    def test_missing_required_plugin_even_with_new_manifest_fails(self):
        (self.bundle / "_internal/PyQt5/Qt5/plugins/platforms/qwindows.dll").unlink()
        self.write_manifest()
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Required dependency absent", result.stderr)

    def test_missing_manifest_fails(self):
        self.manifest.unlink()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_unexpected_file_fails(self):
        (self.bundle / "leftover.txt").write_text("fixture", encoding="utf-8")
        self.assertNotEqual(self.verify().returncode, 0)

    def test_long_dependency_path_passes_without_changing_windows_settings(self):
        path = self.bundle / "_internal" / ("nested-path-" * 12) / ("dependency-name-" * 7 + ".txt")
        path.parent.mkdir(parents=True)
        extended = Path("\\\\?\\" + str(path)) if os.name == "nt" else path
        data = b"long path fixture"
        extended.write_bytes(data)
        # Remove this known fixture via its extended path before the temporary
        # directory cleanup; the host need not enable global long-path support.
        self.addCleanup(extended.unlink, missing_ok=True)
        with self.manifest.open("a", encoding="utf-8") as stream:
            stream.write(f"{hashlib.sha256(data).hexdigest()} *{path.relative_to(self.bundle).as_posix()}\n")
        result = self.verify()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_unsafe_manifest_path_fails_without_reading_outside_bundle(self):
        with self.manifest.open("a", encoding="utf-8") as stream:
            stream.write("0" * 64 + " *../outside.dll\n")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unsafe backend manifest path", result.stderr)

    def test_duplicate_manifest_entry_fails(self):
        first = self.manifest.read_text(encoding="utf-8").splitlines()[0]
        with self.manifest.open("a", encoding="utf-8") as stream:
            stream.write(first + "\n")
        result = self.verify()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Duplicate", result.stderr)


if __name__ == "__main__":
    unittest.main()
