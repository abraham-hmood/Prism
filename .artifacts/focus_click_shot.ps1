param([int]$Pid_,[int]$X,[int]$Y,[string]$Out,[switch]$Hover,[int]$L=0,[int]$T=880,[int]$R=1920,[int]$B=1080)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class FC {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
}
'@
Add-Type -AssemblyName System.Drawing
$h = (Get-Process -Id $Pid_).MainWindowHandle
[void][FC]::ShowWindow($h, 9)
[void][FC]::ShowWindow($h, 3)
[void][FC]::BringWindowToTop($h)
[void][FC]::SetForegroundWindow($h)
Start-Sleep -Milliseconds 900
[void][FC]::SetCursorPos($X, $Y)
Start-Sleep -Milliseconds 500
if (-not $Hover) {
  [FC]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
  Start-Sleep -Milliseconds 90
  [FC]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
}
Start-Sleep -Milliseconds 1200
$bmp = New-Object System.Drawing.Bitmap ($R-$L), ($B-$T)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($L, $T, 0, 0, $bmp.Size)
$path = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Output $path
