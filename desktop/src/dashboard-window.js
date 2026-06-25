'use strict';

const { execFile } = require('child_process');

// The dashboard is shown as a Chromium "app mode" window (msedge/chrome
// --app=...). Those windows have NO browser chrome, and their title is exactly
// the page <title> — "PhotoSync Server" — with no "- Microsoft Edge" suffix, so
// an exact-title match uniquely finds our window (and never a normal browser
// tab that happens to be on the dashboard).
//
// This brings that existing window to the front instead of opening a second
// one. Returns true if a matching window was found and focused; false if there
// was none (so the caller should spawn a fresh window) or if anything failed.

// Enumerate top-level windows, find the one whose title equals $Title, restore
// it if minimized, and force it to the foreground (AttachThreadInput defeats
// Windows' foreground-stealing guard when we're called from the tray).
const PS_SCRIPT = `
$ErrorActionPreference = 'Stop'
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class Fg {
  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, IntPtr pid);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool f);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
}
"@
$target = [IntPtr]::Zero
$cb = [Fg+EnumProc]{
  param($h, $l)
  if ([Fg]::IsWindowVisible($h)) {
    $sb = New-Object System.Text.StringBuilder 512
    [Fg]::GetWindowText($h, $sb, 512) | Out-Null
    if ($sb.ToString() -eq $env:PS_WIN_TITLE) { $script:target = $h; return $false }
  }
  return $true
}
[Fg]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null
if ($target -ne [IntPtr]::Zero) {
  if ([Fg]::IsIconic($target)) { [Fg]::ShowWindow($target, 9) | Out-Null } else { [Fg]::ShowWindow($target, 5) | Out-Null }
  $fg = [Fg]::GetForegroundWindow()
  $tFg = [Fg]::GetWindowThreadProcessId($fg, [IntPtr]::Zero)
  $tMe = [Fg]::GetCurrentThreadId()
  [Fg]::AttachThreadInput($tFg, $tMe, $true) | Out-Null
  [Fg]::SetForegroundWindow($target) | Out-Null
  [Fg]::AttachThreadInput($tFg, $tMe, $false) | Out-Null
  Write-Output 'FOUND'
} else {
  Write-Output 'NONE'
}
`;

function focusWindowByTitle(title) {
  return new Promise((resolve) => {
    // -EncodedCommand sidesteps all quoting; the title is passed via env so it
    // never has to be escaped into the script.
    const encoded = Buffer.from(PS_SCRIPT, 'utf16le').toString('base64');
    execFile(
      'powershell',
      ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded],
      { timeout: 5000, env: { ...process.env, PS_WIN_TITLE: title } },
      (err, stdout) => {
        if (err) return resolve(false); // PS missing/blocked → caller spawns one
        resolve(String(stdout).includes('FOUND'));
      }
    );
  });
}

module.exports = { focusWindowByTitle };
