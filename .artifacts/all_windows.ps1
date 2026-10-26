Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class WAll {
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 public delegate bool EnumProc(IntPtr h, IntPtr l);
 public struct RECT { public int L; public int T; public int R; public int B; }
}
'@
$out = Join-Path (Get-Location) ".artifacts\all_windows.txt"
Set-Content -Path $out -Value ""
$cb = {
  param($h, $l)
  $sb = New-Object System.Text.StringBuilder 512
  [void][WAll]::GetWindowTextW($h, $sb, 512)
  $title = $sb.ToString()
  if ($title -ne "") {
    $pid2 = 0
    [void][WAll]::GetWindowThreadProcessId($h, [ref]$pid2)
    $r = New-Object WAll+RECT
    [void][WAll]::GetWindowRect($h, [ref]$r)
    Add-Content -Path $out -Value ("{0,-28} pid={1} vis={2} rect={3},{4},{5},{6} {7}" -f $title, $pid2, [WAll]::IsWindowVisible($h), $r.L, $r.T, $r.R, $r.B, "")
  }
  return $true
}
[void][WAll]::EnumWindows($cb, [IntPtr]::Zero)
Get-Content $out | Select-Object -First 60
