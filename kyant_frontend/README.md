# Xerver

This folder contains the Compose Desktop frontend for Xerver. The original
`gpu_dashboard.py` remains untouched; the Kyant version uses the copied
`gpu_dashboard_kyant.py` backend through the local API.

## Build the Windows installer

Install Python with PyInstaller and JDK 21, then stage the backend from the
repository root:

```powershell
.\tools\build_backend.ps1
```

Then build the installer from this folder:

```powershell
pwsh -NoProfile -Command "$env:JAVA_HOME='C:\\Path\\To\\JDK-21'; .\gradlew.bat packageExe"
```

The staging script uses `gpu-monitor-backend.spec`, which intentionally keeps
UPX compression disabled. This avoids the extra self-unpacking/compression
layer that commonly causes unsigned monitoring backends to be quarantined.

The generated EXE installer includes the frontend runtime and the bundled
backend. An installed copy stores its machine-specific configuration under
`%APPDATA%\Xerver\config.json`. On first launch, Xerver copies an existing
`%APPDATA%\GPU Monitor` configuration and SSH host-key store so upgrades retain
the user's servers and preferences.

The application and installer use `src/main/resources/app-icon.png` and
`app-icon.ico`. Version `1.0.1` can check the latest published release from
`https://github.com/byebabyblue/gpu-monitor/releases` in Settings. The check
only reads public GitHub release metadata; it does not upload local settings.

The Settings page includes `开机最小化到托盘`. When enabled together with
`开机自启动`, Windows starts Xerver without showing the main window; use the
tray icon to open it. The tray menu is a native Windows menu with an explicit
CJK-capable font, so Chinese labels do not depend on the Compose menu renderer.

If the startup screen cannot reach the local backend, it now falls back to the
offline dashboard after a short timeout and lets the status pill retry the
backend. Backend startup output is kept at
`%APPDATA%\Xerver\logs\backend.log`, which makes it possible to distinguish
a missing/quarantined executable from a configuration or runtime error. The
PyInstaller spec leaves the backend uncompressed to reduce false positives from
security software.

Selected wallpapers are copied to `%LOCALAPPDATA%\Xerver\wallpapers`.
The untouched source is kept under `original`, while the UI loads a downscaled
PNG from `display` sized for the largest connected monitor. Existing wallpaper
assets under `%LOCALAPPDATA%\GPU Monitor\wallpapers` are imported locally when
needed and are never bundled into releases.

## Run from source

Start the backend first, then run the frontend with `gradlew.bat run`. For a
local demo, use `run_kyant_gpu_monitor.ps1 -Demo` from the project root.

Copy `config.example.json` to a local `config.json` and fill in server details.
Never commit the local file: it may contain SSH usernames, addresses, passwords,
or private-key paths.
