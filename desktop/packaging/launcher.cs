// Tiny launcher compiled into PhotoSync Server.exe. Double-clicking it starts the
// bundled Node runtime on the app (which opens the dashboard, shows the tray
// icon, and runs the backup server in the background) and then exits — Node
// keeps running on its own. Built as a Windows app so no console flashes.
using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

class Launcher
{
    static void Main(string[] args)
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        string node = Path.Combine(dir, "node.exe");
        string main = Path.Combine(dir, "desktop", "src", "main.js");

        if (!File.Exists(node) || !File.Exists(main))
        {
            MessageBox.Show(
                "PhotoSync Server files are missing. Please keep PhotoSync Server.exe inside its folder.",
                "PhotoSync Server", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        // --minimized (start in tray, no dashboard) if launched at login.
        bool minimized = Array.IndexOf(args, "--minimized") >= 0;
        string arg = "\"" + main + "\"" + (minimized ? " --minimized" : "");

        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = node,
                Arguments = arg,
                WorkingDirectory = dir,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            Process.Start(psi);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Could not start PhotoSync Server:\n" + ex.Message,
                "PhotoSync Server", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}
