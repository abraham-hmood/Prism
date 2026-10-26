Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class W {
 [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc cb, IntPtr l);
 public delegate bool EnumWindowsProc(IntPtr h, IntPtr l);
 [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 public struct RECT { public int L; public int T; public int R; public int B; }
}
'@

$callback = {
    param($h, $l)
    if ([W]::IsWindowVisible($h)) {
        $s = New-Object System.Text.StringBuilder 512
        [void][W]::GetWindowText($h, $s, 512)
        $r = New-Object W+RECT
        [void][W]::GetWindowRect($h, [ref]$r)
        if ($s.ToString()) {
            Write-Output ($s.ToString() + " :: " + $r.L + "," + $r.T + "," + $r.R + "," + $r.B)
        }
    }
    return $true
}
[W]::EnumWindows($callback, [IntPtr]::Zero) | Select-String -Pattern 'Prism|Compose|Desktop'
