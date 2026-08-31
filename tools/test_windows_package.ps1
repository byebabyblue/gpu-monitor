#requires -Version 7.0
param(
    [Parameter(Mandatory = $true)][string]$AppImage,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [ValidateRange(10, 120)][int]$TimeoutSeconds = 45,
    [ValidateRange(3, 60)][int]$StabilitySeconds = 10,
    [switch]$AutostartEnabled,
    [switch]$RequireChineseTray,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$image = (Resolve-Path -LiteralPath $AppImage).Path
$launcher = Join-Path $image 'Xerver.exe'
$backend = Join-Path $image 'app\resources\backend\gpu-monitor-backend.exe'
foreach ($required in @($launcher, $backend, (Join-Path $image 'runtime\bin\server\jvm.dll'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing packaged file: $required" }
}
& (Join-Path $PSScriptRoot 'verify_backend_bundle.ps1') -BackendDirectory (Split-Path -Parent $backend) | Out-Host

function Read-XerverStartupEntries {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\Microsoft\Windows\CurrentVersion\Run', $false)
    $entries = [ordered]@{}
    try {
        foreach ($name in @('Xerver', 'GPU Monitor')) {
            $entries[$name] = if ($key -and $name -in $key.GetValueNames()) {
                @($key.GetValue($name, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames), "$($key.GetValueKind($name))")
            } else { $null }
        }
    } finally { if ($key) { $key.Dispose() } }
    return ($entries | ConvertTo-Json -Compress)
}
$startupBefore = Read-XerverStartupEntries

$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$runDirectory = Join-Path $output ("startup-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDirectory | Out-Null
$config = Join-Path $runDirectory 'config.json'
$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portProbe.Start()
$port = $portProbe.LocalEndpoint.Port
$portProbe.Stop()
$fixture = @{
    settings = @{ api_port = $port }
    ui = @{ autostart = [bool]$AutostartEnabled; start_minimized_to_tray = $false }
    servers = @()
} | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText($config, $fixture, [Text.UTF8Encoding]::new($false))

# Launch only the shipped executable/runtime, with no system Java and no real
# server credentials. Never install, alter the user's config, or disable AV.
$startInfo = [Diagnostics.ProcessStartInfo]::new($launcher)
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.WindowStyle = 'Hidden'
$startInfo.WorkingDirectory = $runDirectory
$startInfo.Environment['GPU_MONITOR_CONFIG_PATH'] = $config
$startInfo.Environment['GPU_MONITOR_API_PORT'] = "$port"
foreach ($name in @('JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) {
    $startInfo.Environment.Remove($name) | Out-Null
}
$process = [Diagnostics.Process]::Start($startInfo)
$passed = $false
$ready = $false
$lastFailure = ''
$frontendLog = Join-Path $runDirectory 'logs\frontend.log'
try {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $process.Refresh()
        if ($process.HasExited) { throw "Packaged frontend exited early: $($process.ExitCode)" }
        try {
            $response = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/config" -NoProxy -TimeoutSec 2
            $servers = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/servers" -NoProxy -TimeoutSec 2
            $log = if (Test-Path -LiteralPath $frontendLog) { Get-Content -LiteralPath $frontendLog -Raw } else { '' }
            if ($response.settings.api_port -eq $port -and $log.Contains('后端配置读取成功') -and $log.Contains('主界面已就绪')) {
                $ready = $true
                break
            }
        } catch {
            $lastFailure = $_.Exception.Message
        }
        Start-Sleep -Milliseconds 300
    }
    if (-not $ready) { throw "Packaged startup did not become ready. $lastFailure Logs: $runDirectory" }
    # Exercise a normal Settings save that carries an unchanged autostart
    # preference, as the frontend does. It must not register this test copy.
    $settingsPayload = @{ ui = @{ autostart = [bool]$AutostartEnabled; font_scale = 1.02 } } | ConvertTo-Json -Depth 3
    $saved = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/settings" -Method Post `
        -Body $settingsPayload -ContentType 'application/json' -NoProxy -TimeoutSec 5
    if (-not $saved.ok) { throw 'Could not save ordinary settings in the isolated test profile' }
    $stableUntil = [DateTime]::UtcNow.AddSeconds($StabilitySeconds)
    while ([DateTime]::UtcNow -lt $stableUntil) {
        $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/config" -NoProxy -TimeoutSec 2
        Start-Sleep -Milliseconds 500
    }
    $process.Refresh()
    if ($process.HasExited) { throw "Frontend exited after becoming ready: $($process.ExitCode)" }
    $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/config" -NoProxy -TimeoutSec 2
    $log = Get-Content -LiteralPath $frontendLog -Raw
    if ($log -match '前端启动或运行失败|未捕获异常') { throw "Frontend reported a fatal exception: $frontendLog" }
    $tray = [regex]::Match($log, '托盘菜单已初始化 renderer=Swing font=([^\r\n]+) chinese=(true|false)')
    if ($RequireChineseTray -and (-not $tray.Success -or $tray.Groups[2].Value -ne 'true')) {
        throw 'The packaged JVM did not initialize a tray menu with Chinese glyph support'
    }
    $pidMatch = [regex]::Match($log, '前端进程启动[^\r\n]*\bpid=(\d+)')
    if (-not $pidMatch.Success) { throw 'No JVM process id in frontend startup log' }
    $frontend = Get-Process -Id ([int]$pidMatch.Groups[1].Value)
    if ($frontend.HasExited) { throw 'The JVM process exited even though its native launcher is still alive' }
    $backendLog = Get-Content -LiteralPath (Join-Path $runDirectory 'logs\backend.log') -Raw
    if (-not $backendLog -or -not $backendLog.Contains('Local API listening')) {
        throw 'Backend became ready but its own diagnostic log was not recorded'
    }
    $expectedRuntime = Join-Path (Split-Path -Parent $backend) '_internal'
    if ($backendLog -notmatch ('Backend runtime directory=' + [regex]::Escape($expectedRuntime) + '(\r?\n|$)')) {
        throw "Backend did not load its runtime directly from the installed directory: $expectedRuntime"
    }
    if (-not $backendLog.Contains('autostart registry unchanged') -or $backendLog.Contains('User changed autostart')) {
        throw 'Startup or an unrelated Settings save attempted to update autostart'
    }
    if ((Read-XerverStartupEntries) -ne $startupBefore) {
        throw 'Windows startup entries changed during the isolated test; no automatic restoration was attempted'
    }
    & (Join-Path $PSScriptRoot 'verify_backend_bundle.ps1') -BackendDirectory (Split-Path -Parent $backend) | Out-Host
    $passed = $true
    [ordered]@{
        Passed = $true
        Launcher = $launcher
        LauncherPid = $process.Id
        FrontendPid = $frontend.Id
        ApiPort = $port
        WindowTitle = $frontend.MainWindowTitle
        BackendSHA256 = (Get-FileHash -LiteralPath $backend -Algorithm SHA256).Hash
        BackendRuntimeDirectory = $expectedRuntime
        AutostartPreference = [bool]$AutostartEnabled
        StartupEntriesUnchanged = $true
        StabilitySeconds = $StabilitySeconds
        TrayFont = if ($tray.Success) { $tray.Groups[1].Value } else { $null }
        TrayChineseSupported = $tray.Success -and $tray.Groups[2].Value -eq 'true'
        LogDirectory = Join-Path $runDirectory 'logs'
        KeptRunning = [bool]$KeepRunning
    } | ConvertTo-Json | Tee-Object -FilePath (Join-Path $runDirectory 'result.json')
} finally {
    if (-not ($passed -and $KeepRunning)) {
        # Snapshot and stop children before their parents. Native launchers can
        # otherwise detach a backend while the JVM is being terminated.
        $snapshot = @(Get-CimInstance Win32_Process)
        $owned = [Collections.Generic.List[uint32]]::new()
        $owned.Add([uint32]$process.Id)
        for ($index = 0; $index -lt $owned.Count; $index++) {
            foreach ($entry in $snapshot) {
                if ($entry.ParentProcessId -eq $owned[$index] -and -not $owned.Contains($entry.ProcessId)) {
                    $owned.Add($entry.ProcessId)
                }
            }
        }
        for ($index = $owned.Count - 1; $index -ge 0; $index--) {
            $child = Get-Process -Id $owned[$index] -ErrorAction SilentlyContinue
            if ($child -and $child.Path.StartsWith($image + '\', [StringComparison]::OrdinalIgnoreCase)) {
                $child.Kill()
                $child.WaitForExit(5000) | Out-Null
            }
        }
        $process.WaitForExit(5000) | Out-Null
    }
    $process.Dispose()
}
