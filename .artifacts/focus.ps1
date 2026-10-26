param([long]$Handle)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class Focuser {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
 [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
}
'@
$h = [IntPtr]$Handle
[void][Focuser]::ShowWindow($h, 9)
[void][Focuser]::BringWindowToTop($h)
[void][Focuser]::SetForegroundWindow($h)
