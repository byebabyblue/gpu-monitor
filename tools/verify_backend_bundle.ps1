#requires -Version 5.1
param(
    [Parameter(Mandatory = $true)][string]$BackendDirectory,
    [switch]$Json
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$bundleRoot = (Resolve-Path -LiteralPath $BackendDirectory).Path
$bundlePrefix = $bundleRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
$manifest = Join-Path $bundleRoot 'backend-manifest.sha256'
if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
    throw "Missing backend manifest; rebuild the complete onedir bundle: $manifest"
}

$required = @(
    'gpu-monitor-backend.exe',
    '_internal/base_library.zip',
    '_internal/_ctypes.pyd',
    '_internal/PyQt5/Qt5/bin/Qt5Core.dll',
    '_internal/PyQt5/Qt5/bin/Qt5Gui.dll',
    '_internal/PyQt5/Qt5/bin/Qt5Widgets.dll',
    '_internal/PyQt5/Qt5/plugins/platforms/qwindows.dll'
)
$expected = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($line in [IO.File]::ReadAllLines($manifest, [Text.Encoding]::UTF8)) {
    if ($line -notmatch '^([0-9a-fA-F]{64}) \*(.+)$') { throw 'Invalid backend manifest entry' }
    $checksum = $Matches[1]
    $relative = $Matches[2]
    if ($relative -match '[:\\]' -or $relative.StartsWith('/') -or
        @($relative.Split('/') | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
        throw "Unsafe backend manifest path: $relative"
    }
    if ($relative -eq 'backend-manifest.sha256' -or $expected.ContainsKey($relative)) {
        throw "Duplicate or self-referencing backend manifest entry: $relative"
    }
    $expected.Add($relative, $checksum)
}
foreach ($relative in $required) {
    if (-not $expected.ContainsKey($relative)) { throw "Required dependency absent from manifest: $relative" }
}
if (@($expected.Keys | Where-Object { $_ -match '^_internal/python\d{2,3}t?\.dll$' }).Count -ne 1) {
    throw 'Backend manifest must include exactly one Python runtime DLL'
}

$entries = @(Get-Item -LiteralPath $bundleRoot) + @(Get-ChildItem -LiteralPath $bundleRoot -Recurse -Force)
if (@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) {
    throw 'Backend bundle must not contain links or reparse points'
}
$actual = @($entries | Where-Object { -not $_.PSIsContainer -and $_.FullName -ne $manifest })
if ($actual.Count -ne $expected.Count) {
    throw "Backend file count mismatch: expected $($expected.Count), found $($actual.Count)"
}
[long]$totalBytes = 0
foreach ($file in $actual) {
    $relative = $file.FullName.Substring($bundlePrefix.Length).Replace('\', '/')
    if (-not $expected.ContainsKey($relative)) { throw "Unexpected backend file: $relative" }
    if ($file.Length -eq 0 -and $relative -in $required) { throw "Empty required backend file: $relative" }
    $hasher = [Security.Cryptography.SHA256]::Create()
    $readPath = $file.FullName
    if ([IO.Path]::DirectorySeparatorChar -eq '\' -and -not $readPath.StartsWith('\\?\')) {
        $readPath = if ($readPath.StartsWith('\\')) { '\\?\UNC\' + $readPath.Substring(2) } else { '\\?\' + $readPath }
    }
    $stream = [IO.File]::OpenRead($readPath)
    try { $checksum = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $hasher.Dispose() }
    if ($checksum -ne $expected[$relative]) { throw "Backend checksum mismatch: $relative" }
    $totalBytes += $file.Length
}
$result = [PSCustomObject]@{
    BackendDirectory = $bundleRoot
    Files = $actual.Count
    Bytes = $totalBytes
    SHA256Verified = $true
}
if ($Json) { $result | ConvertTo-Json -Compress } else { $result }
