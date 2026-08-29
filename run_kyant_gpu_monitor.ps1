param(
    [switch]$Demo
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$backendScript = Join-Path $root 'gpu_dashboard_kyant.py'
$frontend = Join-Path $root 'kyant_frontend'
$gradleWrapper = Join-Path $frontend 'gradlew.bat'
$appDataRoot = if ($env:APPDATA) { $env:APPDATA } else { $root }
$logDirectory = Join-Path $appDataRoot 'Xerver\logs'
$backendStdoutLog = Join-Path $logDirectory 'backend-source.stdout.log'
$backendStderrLog = Join-Path $logDirectory 'backend-source.stderr.log'

if (-not (Test-Path -LiteralPath $backendScript -PathType Leaf)) {
    throw "Backend script not found: $backendScript"
}
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}

New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

$python = Get-Command pythonw.exe -ErrorAction SilentlyContinue
if ($null -eq $python) {
    $python = Get-Command pyw.exe -ErrorAction SilentlyContinue
}
if ($null -eq $python) {
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
}
if ($null -eq $python) {
    throw 'A usable Python launcher was not found on PATH.'
}

$backendArgs = @($backendScript)
if ($Demo) {
    $backendArgs += '--demo'
}
$backendArgs += @('--backend-only', '--api-port', '8766')
$backendProcess = Start-Process -FilePath $python.Source `
    -ArgumentList $backendArgs `
    -WorkingDirectory $root `
    -RedirectStandardOutput $backendStdoutLog `
    -RedirectStandardError $backendStderrLog `
    -WindowStyle Hidden `
    -PassThru

try {
    Push-Location $frontend
    & $gradleWrapper 'run'
    if ($LASTEXITCODE -ne 0) {
        throw "Kyant frontend exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
    if ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force -ErrorAction SilentlyContinue
    }
}
