param([int]$ProcId)
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class WEnum2 {
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr l);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 public delegate bool EnumWindowsProc(IntPtr h, IntPtr l);
 public struct RECT { public int L; public int T; public int R; public int B; }
}
'@
$target = $ProcId
$out = Join-Path (Get-Location) ".artifacts\windows_of.txt"
Set-Content -Path $out -Value ""
$callback = {
  param($h, $l)
  $owner = 0
  [void][WEnum2]::GetWindowThreadProcessId($h, [ref]$owner)
  if ($owner -eq $target) {
    $sb = New-Object System.Text.StringBuilder 512
    [void][WEnum2]::GetWindowTextW($h, $sb, 512)
    $r = New-Object WEnum2+RECT
    [void][WEnum2]::GetWindowRect($h, [ref]$r)
    Add-Content -Path $out -Value ("handle=" + $h + " visible=" + [WEnum2]::IsWindowVisible($h) + " rect=" + $r.L + "," + $r.T + "," + $r.R + "," + $r.B + " title=" + $sb.ToString())
  }
  return $true
}
[void][WEnum2]::EnumWindows($callback, [IntPtr]::Zero)
Get-Content $out
