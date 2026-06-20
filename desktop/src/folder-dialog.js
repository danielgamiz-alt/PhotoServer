'use strict';

const { spawn } = require('child_process');

/**
 * Pops the native Windows "choose folder" dialog and resolves to the chosen
 * path (or null if cancelled). Browsers can't read real filesystem paths, so
 * the dashboard's "Browse…" button calls back here.
 *
 * FolderBrowserDialog requires a single-threaded apartment, hence -STA.
 */
function pickFolder(currentPath) {
  const ps = [
    'Add-Type -AssemblyName System.Windows.Forms;',
    '$d = New-Object System.Windows.Forms.FolderBrowserDialog;',
    "$d.Description = 'Choose where to store backed-up photos';",
    '$d.ShowNewFolderButton = $true;',
    currentPath ? `$d.SelectedPath = '${currentPath.replace(/'/g, "''")}';` : '',
    "if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($d.SelectedPath) }",
  ].join(' ');

  return new Promise((resolve) => {
    let out = '';
    const child = spawn(
      'powershell',
      ['-NoProfile', '-STA', '-NonInteractive', '-Command', ps],
      { windowsHide: true }
    );
    child.stdout.on('data', (d) => (out += d.toString()));
    child.on('error', () => resolve(null));
    child.on('close', () => {
      const p = out.trim();
      resolve(p.length > 0 ? p : null);
    });
  });
}

module.exports = { pickFolder };
