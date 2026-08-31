"""Update only Xerver's startup entries, and only for an explicit user change."""

from pathlib import Path
import sys


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "Xerver"
LEGACY_VALUE_NAME = "GPU Monitor"


def update_autostart(enabled, config_path, frontend_exe=""):
    """Return whether the registry changed; never call this during startup.

    Reading an already-correct entry does not open the key for writing. Tests
    replace winreg with an in-memory registry; they never touch real startup.
    """
    if sys.platform != "win32":
        return False
    import winreg

    command = None
    if enabled:
        frontend = Path(frontend_exe).expanduser().resolve() if frontend_exe else None
        launcher = Path(config_path).resolve().parent / "start_gpu_monitor.ps1"
        # Installed applications launch their signed/unsigned native frontend
        # directly. The script fallback is only for running from source.
        if frontend and frontend.is_file():
            command = f'"{frontend}"'
        elif launcher.is_file():
            command = (
                'powershell.exe -NoLogo -NoProfile -WindowStyle Hidden '
                f'-File "{launcher}"'
            )
        else:
            raise ValueError("Autostart launcher was not found")

    existing = {}
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_QUERY_VALUE) as key:
            for name in (VALUE_NAME, LEGACY_VALUE_NAME):
                try:
                    existing[name] = winreg.QueryValueEx(key, name)
                except FileNotFoundError:
                    pass
    except FileNotFoundError:
        pass

    needs_write = enabled and existing.get(VALUE_NAME) != (command, winreg.REG_SZ)
    remove = [
        name for name in (VALUE_NAME, LEGACY_VALUE_NAME)
        if name in existing and (not enabled or name == LEGACY_VALUE_NAME)
    ]
    if not needs_write and not remove:
        return False

    if needs_write:
        key = winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE)
    else:
        try:
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE)
        except FileNotFoundError:
            return False  # Another actor removed the key after the read.
    with key:
        if needs_write:
            winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ, command)
        for name in remove:
            try:
                winreg.DeleteValue(key, name)
            except FileNotFoundError:
                pass
    return True
