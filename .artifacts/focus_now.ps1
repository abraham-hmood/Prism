Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class Win {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
'@
$target = [IntPtr]::Zero
[Win]::EnumWindows({ param($h,$l)
  if ([Win]::IsWindowVisible($h)) {
    $sb = New-Object System.Text.StringBuilder 512
    [void][Win]::GetWindowText($h, $sb, 512)
    $t = $sb.ToString()
    $pidv = 0
    [void][Win]::GetWindowThreadProcessId($h, [ref]$pidv)
    if ($t -like '*Prism*') { Write-Output ("WINDOW " + $h + " pid=" + $pidv + " title=" + $t) }
  }
  return $true
}, [IntPtr]::Zero) | Out-Null

$p = Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like '*Prism*' } | Select-Object -First 1
if ($p) {
  $h = $p.MainWindowHandle
  [void][Win]::ShowWindow($h, 9)
  [void][Win]::SetWindowPos($h, [IntPtr](-1), 0, 0, 1280, 840, 0x0040)
  [void][Win]::SetForegroundWindow($h)
  [void][Win]::SetWindowPos($h, [IntPtr](-2), 0, 0, 1280, 840, 0x0040)
  Write-Output ("FOCUSED pid=" + $p.Id + " handle=" + $h + " title=" + $p.MainWindowTitle)
} else {
  Write-Output "NO PRISM"
}
