param(
  [string]$ApkPath = "C:\Users\paul\Documents\.projects\game-app\app-debug.apk"
)

$ErrorActionPreference = 'Stop'

if (!(Test-Path -LiteralPath $ApkPath)) {
  throw "APK not found: $ApkPath"
}

$deviceLines = adb devices | Select-Object -Skip 1
$devices = @()
foreach ($line in $deviceLines) {
  if ([string]::IsNullOrWhiteSpace($line)) { continue }
  $parts = $line -split "`t"
  if ($parts.Length -ge 2 -and $parts[1] -eq 'device') {
    $devices += $parts[0]
  }
}

if ($devices.Count -eq 0) {
  throw 'No connected adb devices in the "device" state were found.'
}

foreach ($serial in $devices) {
  Write-Host "Installing on $serial..."
  adb -s $serial install -r "$ApkPath"
  if ($LASTEXITCODE -ne 0) {
    throw "Install failed on $serial"
  }
}

Write-Host "Done. Installed to $($devices.Count) device(s)."
