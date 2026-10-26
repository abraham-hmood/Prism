$ports = 8080,8097,8098,11134
foreach ($port in $ports) {
  $conns = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
  if ($null -eq $conns) { Write-Output ("port " + $port + ": free"); continue }
  $conns | ForEach-Object {
    $proc = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
    $name = if ($null -ne $proc) { $proc.ProcessName } else { "?" }
    Write-Output ("port " + $port + " " + $_.State + " pid=" + $_.OwningProcess + " " + $name)
  }
}
