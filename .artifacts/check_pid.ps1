$p = Get-CimInstance Win32_Process -Filter "ProcessId=26636"
if ($null -eq $p) { Write-Output "not running"; exit }
Write-Output $p.CommandLine
