Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" | ForEach-Object {
  $cmd = $_.CommandLine
  if ($null -eq $cmd) { $cmd = "" }
  if ($cmd.Length -gt 220) { $cmd = $cmd.Substring(0, 220) }
  Write-Output ("PID=" + $_.ProcessId + " PPID=" + $_.ParentProcessId + " START=" + $_.CreationDate + " CMD=" + $cmd)
}
