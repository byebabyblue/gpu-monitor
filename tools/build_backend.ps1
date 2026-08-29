param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$spec = Join-Path $root 'gpu-monitor-backend.spec'
$distPath = Join-Path $root 'work\pyinstaller-dist'
$buildPath = Join-Path $root 'work\pyinstaller-build'
$target = Join-Path $root 'kyant_frontend\appResources\backend\gpu-monitor-backend.exe'

if (-not (Test-Path -LiteralPath $spec -PathType Leaf)) {
    throw "PyInstaller spec not found: $spec"
}

New-Item -ItemType Directory -Path $distPath -Force | Out-Null
New-Item -ItemType Directory -Path $buildPath -Force | Out-Null

& $Python -m PyInstaller `
    --noconfirm `
    --clean `
    --distpath $distPath `
    --workpath $buildPath `
    $spec
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller failed with exit code $LASTEXITCODE."
}

$built = Join-Path $distPath 'gpu-monitor-backend.exe'
if (-not (Test-Path -LiteralPath $built -PathType Leaf)) {
    throw "PyInstaller did not create the backend executable: $built"
}

New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
Copy-Item -LiteralPath $built -Destination $target -Force
Write-Host "Bundled backend staged at $target"
