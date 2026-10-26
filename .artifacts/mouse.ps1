param([string]$Action = "click", [int]$X = 0, [int]$Y = 0, [int]$Amount = 0)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class M {
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
}
'@
[void][M]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 120
if ($Action -eq "click") {
    [M]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    [M]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
} elseif ($Action -eq "scroll") {
    [M]::mouse_event(0x0800, 0, 0, $Amount, [UIntPtr]::Zero)
}
