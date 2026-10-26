param([int]$X,[int]$Y,[switch]$Hover)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class MS4 {
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
}
'@
[void][MS4]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 400
if (-not $Hover) {
  [MS4]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 80
  [MS4]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
}
Start-Sleep -Milliseconds 700
Write-Output ("at " + $X + "," + $Y)
