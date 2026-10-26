Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  $line = "{0} created {1}" -f $_.ProcessId, $_.CreationDate
  if ($_.CommandLine -and ($_.CommandLine -match "compose.application" -or $_.CommandLine -match "prism.desktop" -or $_.CommandLine -match "desktop.jar")) {
    $line = $line + "  <-- DESKTOP RUN"
  }
  Write-Output $line
}
Write-Output "--- listeners ---"
Get-NetTCPConnection -State Listen -LocalPort 8097 -ErrorAction SilentlyContinue | ForEach-Object { "8097 owner " + $_.OwningProcess }
