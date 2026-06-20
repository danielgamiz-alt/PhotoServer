# Builds a portable PhotoServer: a self-contained folder with PhotoServer.exe
# you double-click. No install, no admin. Output: desktop/dist/PhotoServer/
#
# Usage:  npm run package      (from the desktop folder)

$ErrorActionPreference = "Stop"
$desktop = $PSScriptRoot
$root = Split-Path $desktop -Parent          # repo root (has server/ + desktop/)
$dist = Join-Path $desktop "dist\PhotoServer"
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"

Write-Host "Building portable PhotoServer..." -ForegroundColor Cyan

# 1. Fresh dist folder
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Force $dist | Out-Null

# 2. Make sure icons exist
node (Join-Path $desktop "assets\generate-icons.js") | Out-Null

# 3. Compile the launcher -> PhotoServer.exe (windowed, with our icon)
$exePath = Join-Path $dist "PhotoServer.exe"
$iconPath = Join-Path $desktop "assets\app.ico"
$srcPath = Join-Path $desktop "packaging\launcher.cs"
& $csc /nologo /target:winexe "/out:$exePath" "/win32icon:$iconPath" `
    /reference:System.Windows.Forms.dll "$srcPath"
if (-not (Test-Path $exePath)) { throw "launcher compile failed" }

# 4. Bundle the Node runtime
Copy-Item (Get-Command node).Source (Join-Path $dist "node.exe")

# 5. Copy the app (server is zero-dependency; desktop carries node_modules)
$serverDst = Join-Path $dist "server\src"
New-Item -ItemType Directory -Force $serverDst | Out-Null
Copy-Item (Join-Path $root "server\src\*") $serverDst -Recurse
Copy-Item (Join-Path $root "server\package.json") (Join-Path $dist "server\package.json")

$deskDst = Join-Path $dist "desktop"
foreach ($sub in @("src", "public", "assets")) {
    $d = Join-Path $deskDst $sub
    New-Item -ItemType Directory -Force $d | Out-Null
    Copy-Item (Join-Path $desktop "$sub\*") $d -Recurse
}
Copy-Item (Join-Path $desktop "package.json") (Join-Path $deskDst "package.json")

# node_modules (sharp, systray2 + helper exe, node-notifier + helper)
Write-Host "Copying node_modules (this is the big part)..." -ForegroundColor DarkGray
Copy-Item (Join-Path $desktop "node_modules") (Join-Path $deskDst "node_modules") -Recurse

# 6. A short readme for the folder
@"
PhotoServer (portable)

Double-click PhotoServer.exe to start. Your photo dashboard opens in your
browser, a green icon appears in the system tray (bottom-right), and the
backup server runs in the background.

First run: open Settings and set your backup folder (e.g. an external SSD).
Quit any time from the tray icon. Keep all files in this folder together.
"@ | Set-Content (Join-Path $dist "READ ME.txt") -Encoding utf8

# 7. Report size
$size = [math]::Round((Get-ChildItem $dist -Recurse | Measure-Object Length -Sum).Sum / 1MB, 0)
Write-Host "Done -> $dist  (${size} MB)" -ForegroundColor Green
