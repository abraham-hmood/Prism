Add-Type @'
using System;
using System.Runtime.InteropServices;
public class WF {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
 [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
}
'@
$h = (Get-Process -Id 39320).MainWindowHandle
[void][WF]::ShowWindow($h, 9)
[void][WF]::SetWindowPos($h, [IntPtr](-1), 48, 48, 1280, 840, 0x0040)
[void][WF]::BringWindowToTop($h)
[void][WF]::SetForegroundWindow($h)
[void][WF]::SetCursorPos(700, 450)
Start-Sleep -Milliseconds 150
[WF]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
[WF]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 300
[void][WF]::SetWindowPos($h, [IntPtr](-2), 48, 48, 1280, 840, 0x0040)
Write-Output ("focused " + $h)
