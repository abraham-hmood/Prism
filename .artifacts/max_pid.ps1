param([int]$Pid_)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class WM3 {
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
 public struct RECT { public int L; public int T; public int R; public int B; }
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
}
'@
$h = (Get-Process -Id $Pid_).MainWindowHandle
[void][WM3]::ShowWindow($h, 3)
Start-Sleep -Milliseconds 800
[void][WM3]::BringWindowToTop($h)
[void][WM3]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 500
$r = New-Object WM3+RECT
[void][WM3]::GetWindowRect($h, [ref]$r)
Write-Output ($r.L.ToString() + " " + $r.T.ToString() + " " + $r.R.ToString() + " " + $r.B.ToString())
