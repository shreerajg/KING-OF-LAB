# dl_ffmpeg.ps1 — Downloads the latest FFmpeg release and extracts ffmpeg.exe
# Run from the KING project root: powershell -File scripts\dl_ffmpeg.ps1

$ErrorActionPreference = "Stop"

$url     = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
$zipPath = Join-Path $env:TEMP "ffmpeg-release-essentials.zip"
$destDir = Join-Path (Get-Location) "ffmpeg"

Write-Host "[1/4] Creating ffmpeg\ directory..." -ForegroundColor Cyan
if (-not (Test-Path $destDir)) {
    New-Item -ItemType Directory -Path $destDir | Out-Null
}

Write-Host "[2/4] Downloading FFmpeg release essentials (~75 MB) ..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
Write-Host "      Saved to: $zipPath"

Write-Host "[3/4] Extracting ffmpeg.exe ..." -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
$entry   = $archive.Entries | Where-Object { $_.Name -eq "ffmpeg.exe" } | Select-Object -First 1

if ($null -eq $entry) {
    $archive.Dispose()
    Write-Error "ffmpeg.exe not found inside the zip archive."
    exit 1
}

$outPath = Join-Path $destDir "ffmpeg.exe"
[System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $outPath, $true)
$archive.Dispose()

Write-Host "[4/4] Done!" -ForegroundColor Green
$item = Get-Item $outPath
Write-Host ("    File : " + $item.FullName)
Write-Host ("    Size : {0:N2} MB" -f ($item.Length / 1MB))
Write-Host ("    Date : " + $item.LastWriteTime)
