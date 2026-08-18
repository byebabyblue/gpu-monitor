# -*- mode: python ; coding: utf-8 -*-

import sys
from pathlib import Path


project_root = Path(SPECPATH)
python_root = Path(sys.base_prefix)

# Conda's _ctypes.pyd links to ffi.dll from Library/bin, while the official
# Windows Python build commonly links to libffi-7.dll from DLLs.  PyInstaller
# does not always discover the Conda variant, so collect the available names
# explicitly and preserve their dependency filenames in the bundle root.
ffi_binaries = []
for directory in (python_root / "DLLs", python_root / "Library" / "bin"):
    for name in (
        "ffi.dll",
        "ffi-7.dll",
        "ffi-8.dll",
        "libffi-7.dll",
        "libffi-8.dll",
    ):
        candidate = directory / name
        if candidate.is_file():
            ffi_binaries.append((str(candidate), "."))

a = Analysis(
    [str(project_root / "gpu_dashboard_kyant.py")],
    pathex=[str(project_root)],
    binaries=ffi_binaries,
    datas=[],
    hiddenimports=[],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="gpu-monitor-backend",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
