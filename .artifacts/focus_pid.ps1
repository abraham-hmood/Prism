param([int]$Pid_,[int]$W=1280,[int]$H=840)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class WF2 {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
 public struct RECT { public int L; public int T; public int R; public int B; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
}
'@
$h = (Get-Process -Id $Pid_).MainWindowHandle
[void][WF2]::ShowWindow($h, 9)
[void][WF2]::SetWindowPos($h, [IntPtr](-2), 40, 40, $W, $H, 0x0040)
[void][WF2]::BringWindowToTop($h)
[void][WF2]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 600
$r = New-Object WF2+RECT
[void][WF2]::GetWindowRect($h, [ref]$r)
Write-Output ($r.L.ToString() + " " + $r.T.ToString() + " " + $r.R.ToString() + " " + $r.B.ToString())
