param([int]$L=0,[int]$T=0,[int]$R=1366,[int]$B=900,[string]$Out=".artifacts\desktop.png")
Add-Type -AssemblyName System.Drawing
$w = $R - $L
$h = $B - $T
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($L, $T, 0, 0, $bmp.Size)
$path = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
$bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output $path
