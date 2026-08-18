# GPU Monitor · Kyant Glass

This folder contains the Compose Desktop frontend for GPU Monitor. The original
`gpu_dashboard.py` remains untouched; the Kyant version uses the copied
`gpu_dashboard_kyant.py` backend through the local API.

## Build the Windows installer

Install JDK 21, then run from this folder:

```powershell
pwsh -NoProfile -Command "$env:JAVA_HOME='C:\\Path\\To\\JDK-21'; .\gradlew.bat packageExe"
```

The generated EXE installer includes the frontend runtime and the bundled
backend. An installed copy stores its machine-specific configuration under
`%APPDATA%\GPU Monitor\config.json`.

The application and installer use `src/main/resources/app-icon.png` and
`app-icon.ico`. Version `0.2.5` can check the latest published release from
`https://github.com/byebabyblue/gpu-monitor/releases` in Settings. The check
only reads public GitHub release metadata; it does not upload local settings.

Selected wallpapers are copied to `%LOCALAPPDATA%\GPU Monitor\wallpapers`.
The untouched source is kept under `original`, while the UI loads a downscaled
PNG from `display` sized for the largest connected monitor.

## Run from source

Start the backend first, then run the frontend with `gradlew.bat run`. For a
local demo, use `run_kyant_gpu_monitor.ps1 -Demo` from the project root.

Copy `config.example.json` to a local `config.json` and fill in server details.
Never commit the local file: it may contain SSH usernames, addresses, passwords,
or private-key paths.
