#requires -Version 5.1
param(
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$spec = Join-Path $root 'gpu-monitor-backend.spec'
$distPath = Join-Path $root 'work\pyinstaller-dist'
$buildPath = Join-Path $root 'work\pyinstaller-build'
$target = Join-Path $root 'kyant_frontend\appResources\common\backend'
$verifier = Join-Path $PSScriptRoot 'verify_backend_bundle.ps1'

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

$built = Join-Path $distPath 'gpu-monitor-backend'
if (-not (Test-Path -LiteralPath (Join-Path $built 'gpu-monitor-backend.exe') -PathType Leaf)) {
    throw "PyInstaller did not create the onedir backend: $built"
}

$stage = Join-Path $root ('work\pyinstaller-stage\' + [Guid]::NewGuid().ToString('N') + '\backend')
New-Item -ItemType Directory -Path (Split-Path -Parent $stage) -Force | Out-Null
Copy-Item -LiteralPath $built -Destination $stage -Recurse
$stagePrefix = $stage.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$manifestLines = @(Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName | ForEach-Object {
    $relative = $_.FullName.Substring($stagePrefix.Length).Replace('\', '/')
    $hasher = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($_.FullName)
    try { $checksum = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $hasher.Dispose() }
    "$checksum *$relative"
})
[IO.File]::WriteAllLines(
    (Join-Path $stage 'backend-manifest.sha256'), $manifestLines, [Text.UTF8Encoding]::new($false)
)
& $verifier -BackendDirectory $stage | Out-Host

function Assert-WorkspaceDirectory([string]$directory) {
    $absolute = [IO.Path]::GetFullPath($directory)
    if (-not $absolute.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to move a directory outside the workspace: $absolute"
    }
    for ($ancestor = $absolute; $ancestor -ne $root; $ancestor = Split-Path -Parent $ancestor) {
        if ((Test-Path -LiteralPath $ancestor) -and
            ((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Refusing to move through a linked directory: $ancestor"
        }
    }
}

# Replace the whole tree, never overlay it or copy only the launcher. Preserve
# the preceding stage outside appResources so it cannot enter the installer.
$backup = Join-Path $root ('work\pyinstaller-previous\' + [Guid]::NewGuid().ToString('N') + '\backend')
foreach ($directory in @($stage, $target, $backup)) { Assert-WorkspaceDirectory $directory }
New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
try {
    if (Test-Path -LiteralPath $target) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $backup) -Force | Out-Null
        Move-Item -LiteralPath $target -Destination $backup
        Write-Host "Previous staged backend preserved at $backup"
    }
    Move-Item -LiteralPath $stage -Destination $target
} catch {
    if ((Test-Path -LiteralPath $backup) -and -not (Test-Path -LiteralPath $target)) {
        Move-Item -LiteralPath $backup -Destination $target
    }
    throw
}
& $verifier -BackendDirectory $target | Out-Host
Write-Host "Complete backend directory staged at $target"
