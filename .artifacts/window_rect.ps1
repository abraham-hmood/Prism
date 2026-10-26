Add-Type @'
using System;
using System.Runtime.InteropServices;
public class WR {
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
 public struct RECT { public int L; public int T; public int R; public int B; }
}
'@
$p = Get-Process -Id 39320
$h = $p.MainWindowHandle
$r = New-Object WR+RECT
[void][WR]::GetWindowRect($h, [ref]$r)
Write-Output ("handle=" + $h + " rect=" + $r.L + "," + $r.T + "," + $r.R + "," + $r.B)
[void][WR]::ShowWindow($h, 9)
[void][WR]::SetForegroundWindow($h)
