# Xerver

## 1.2.4

- 数据后端使用无窗口服务，不再创建旧版卡片、详情页、图表和界面定时器。
- 旧版独立界面保留，图表依赖仅在该入口启动时加载；采集与接口功能保持一致。


## 1.2.3

- 网络检测并入“通信与刷新频率”，保留按服务器地址共享结果和手动检测。
- 中文界面，正文使用正常字重；新 100% 相当于此前 135%，支持上下调整。
- 减少清透的白色蒙层，增强玻璃色调，修复放大字号与窄窗口排版。


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

The staging script uses `gpu-monitor-backend.spec` in **onedir** mode, with
UPX disabled. Python, Qt and other native dependencies are installed under
`app/resources/backend/_internal`; the backend no longer unpacks its runtime
into a new temporary directory on every launch. The download remains a single
installer containing the complete frontend and backend. Users do not need a
separate Python installation.

The entire backend directory is staged together, with a SHA-256 manifest.
`tools/verify_backend_bundle.ps1` checks every file, the Python runtime and Qt
platform plugin. Packaging verifies both the source bundle and the resources
prepared by Compose. Missing, changed or unexpected dependencies stop the build.
Previous staging output is preserved under `work/pyinstaller-previous`, outside
application resources. Never redistribute just `gpu-monitor-backend.exe`.

The generated EXE installer includes the frontend runtime and the bundled
backend. An installed copy stores its machine-specific configuration under
`%APPDATA%\Xerver\config.json`. On first launch, Xerver copies an existing
`%APPDATA%\GPU Monitor` configuration and SSH host-key store so upgrades retain
the user's servers and preferences.

The application and installer use `src/main/resources/app-icon.png` and
`app-icon.ico`. Settings can check the latest published release from
`https://github.com/byebabyblue/gpu-monitor/releases` in Settings. The check
only reads public GitHub release metadata; it does not upload local settings.

This local build is `1.2.2`, based on 1.2.1 with the original interface.
See the [build notes](../docs/releases/v1.2.2.md) for changes.

Software update checks are manual. Settings keeps the “立即检查” button and
the public latest-release fallback; no update interval is stored or scheduled.

Node now shows network probes originating on each remote GPU server: Baidu,
GitHub, Tsinghua PyPI, and the official PyTorch wheel index. ICMP ping and HTTPS
are reported separately. The remote host needs `ping` and `curl`; unavailable
tools are shown explicitly. Settings → 网络检测 provides a 12–168 hour automatic
interval (default: 24), “保存间隔”, and “立即检测”. One detection round per server
address is shared across nodes, including different SSH ports/users. In-flight
requests are coalesced, and cached results survive application restarts. The
shared-result scope is shown in the network card. Background probes retain
bounded timeouts and normal TLS certificate validation.
HTTPS timing includes DNS, connection, TLS, and the response, not throughput.

GPU utilization and VRAM charts share the selected time range and a pause/resume
control. Fixed epoch-aligned buckets replace moving sample-index decimation;
completed buckets stay stable as new samples arrive. One hour of recent raw
data is retained, with 30-second weighted buckets up to six hours and
120-second buckets up to 24 hours. History is in memory and resets on backend restart.
VRAM is shown in GiB with remaining capacity and a warning at 90% usage.

The Settings page includes `开机最小化到托盘`. When enabled together with
`开机自启动`, Windows starts Xerver without showing the main window; use the
tray icon to open it. The tray icon uses the system tray, while its context menu
uses Swing's Unicode text rendering instead of AWT's native `MenuItem` font peer.
Font selection uses locale-independent family names and checks actual Chinese
glyph coverage. A machine without any usable CJK font receives readable English
labels instead of squares. The menu works with the main window hidden, stays
within the monitor work area, and closes on selection, Escape or focus loss.

Normal startup never registers or repairs Windows startup entries. Only an
explicit change to the `开机自启动` setting may update them; saving other settings
does not rewrite the entry. The update checks the existing registry value first
and avoids writes when it already matches. Installed copies register `Xerver.exe`
directly; the PowerShell launcher fallback is only for source-based installations.
If an external tool removes the startup entry, Xerver does not silently recreate
it. The user can turn the setting off and on to request registration again.

If the startup screen cannot reach the local backend, it falls back to the
offline dashboard after a short timeout and lets the status pill retry the
backend. All backend launch stages (including config/log preparation) are guarded
so a missing or blocked backend does not close the frontend. Backend stdin uses a
closed pipe: Java's `Redirect.DISCARD` is output-only and must not be used for input.

Settings includes **错误日志 / 打开日志文件夹**. Logs are local-only:

- `%APPDATA%\Xerver\logs\frontend.log`: runtime details, backend launch errors,
  child exit codes, API readiness and uncaught frontend exceptions.
- `%APPDATA%\Xerver\logs\backend.log`: backend startup milestones, local API
  binding, Python/thread exceptions, native fault traces and windowed stdout/stderr.
  An early PyInstaller hook installs logging before Qt/dependency runtime hooks.
- A custom `GPU_MONITOR_CONFIG_PATH` places logs beside that config instead.
- If the normal directory is unwritable, logging falls back to
  `%TEMP%\Xerver\logs`. Logs over 4 MiB retain one `.previous.log` copy;
  backend rotation occurs only before launching a new child.

Configuration contents, passwords and API request bodies are not written by the
frontend diagnostics or uploaded automatically. Do not include `config.json`
when sharing error logs. The installer is currently unsigned; no build change
can guarantee that every antivirus will accept it.

## Verify a packaged Windows build

Run `gradlew.bat test createDistributable` first, then from the repository root:

```powershell
.\tools\test_windows_package.ps1 `
  -AppImage .\kyant_frontend\build\compose\binaries\main\app\Xerver `
  -OutputDirectory .\work\package-tests
```

This launches the native executable with its bundled JVM and backend, a fresh
empty config, and an unused loopback port. It checks both API responses and
frontend readiness logs before passing, then terminates only its own process tree.
Use `-KeepRunning` for manual window checks. Before publishing, also run this
check against the final installer's extracted application image, not just the
intermediate build directory.

Repeat with `-AutostartEnabled` to cover an existing enabled preference. Both
cases use empty server lists, save an unrelated setting, verify the backend
runtime is loaded from `_internal`, and compare startup entries before/after.
The test never enables/disables real startup entries or modifies antivirus
settings. Run `python -m unittest discover -s tests -v` for the backend regression
tests; startup toggling itself is tested with an in-memory registry.

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
