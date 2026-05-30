param(
  [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
  [string]$PackageName = 'com.aistudio.reactiontimer.kxmpzq',
  [switch]$SkipBuild,
  [switch]$Rebuild,
  [switch]$ForceUninstall
)

$ErrorActionPreference = 'Stop'

function Write-Info([string]$Message) {
  Write-Host "[info] $Message"
}

function Resolve-GradleCommand {
  $wrapper = Join-Path $ProjectRoot 'gradlew.bat'
  if (Test-Path $wrapper) {
    return @($wrapper, ':app:assembleDebug')
  }

  if (Get-Command gradle -ErrorAction SilentlyContinue) {
    return @('gradle', ':app:assembleDebug')
  }

  throw 'Neither gradlew.bat nor gradle was found.'
}

function Get-ConnectedDevices {
  $lines = & adb devices | Select-Object -Skip 1
  $devices = foreach ($line in $lines) {
    if ($line -match '^(\S+)\s+device$') {
      $matches[1]
    }
  }

  @($devices)
}

function Get-DebugApkPath {
  $apk = Join-Path $ProjectRoot 'app\build\outputs\apk\debug\app-debug.apk'
  if (Test-Path $apk) {
    return $apk
  }

  $candidate = Get-ChildItem -Path (Join-Path $ProjectRoot 'app\build\outputs\apk\debug') -Filter '*.apk' -File -ErrorAction SilentlyContinue |
    Select-Object -First 1

  if ($candidate) {
    return $candidate.FullName
  }

  throw 'Debug APK was not found. Run the build step first.'
}

function Stop-AppOnDevice([string]$Device, [string]$PackageName) {
  Write-Info "Stopping any running $PackageName instance on $Device"
  & adb -s $Device shell am force-stop $PackageName | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Warning "Could not force-stop $PackageName on $Device. It may not be installed yet."
  }
}

function Install-AppOnDevice([string]$Device, [string]$PackageName, [string]$ApkPath, [bool]$ForceUninstall) {
  Stop-AppOnDevice -Device $Device -PackageName $PackageName

  if ($ForceUninstall) {
    Write-Info "Force uninstall enabled. Removing $PackageName from $Device before install."
    & adb -s $Device uninstall $PackageName | Out-Null
  }

  Write-Info "Installing/updating $PackageName on $Device"
  $installOutput = & adb -s $Device install -r -g $ApkPath 2>&1
  if ($LASTEXITCODE -eq 0) {
    $installOutput | ForEach-Object { Write-Info $_ }
    return
  }

  $installText = ($installOutput | Out-String).Trim()
  Write-Warning "Install/update failed on $Device. $installText"

  $canRetryAfterUninstall =
    $installText -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' -or
    $installText -match 'INSTALL_FAILED_VERSION_DOWNGRADE' -or
    $installText -match 'INSTALL_FAILED_INVALID_APK' -or
    $installText -match 'signatures do not match'

  if (-not $canRetryAfterUninstall) {
    throw "Failed to install APK on $Device."
  }

  Write-Warning "Retrying after uninstall on $Device because install failed with an update/signature/downgrade conflict."
  & adb -s $Device uninstall $PackageName | Out-Null
  $retryOutput = & adb -s $Device install -r -g $ApkPath 2>&1
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to install APK on $Device after uninstall retry. $($retryOutput | Out-String)"
  }
  $retryOutput | ForEach-Object { Write-Info $_ }
}

function Get-DeviceRolePreset([string]$Device) {
  switch ($Device) {
    'DMIFHU7HUG9PKVVK' { return @{ Role = 'controller'; Label = 'OnePlus CPH2399 controller' } }
    '31071FDH2008FK' { return @{ Role = 'follower'; Label = 'Google Pixel 7 follower' } }
    '29fec8f8' { return @{ Role = 'follower'; Label = 'Xiaomi 23021RAA2Y follower' } }
    '4c637b9e' { return @{ Role = 'display'; Label = 'Xiaomi 2410CRP4CG display' } }
    default { return @{ Role = 'follower'; Label = "Unknown device $Device follower" } }
  }
}

function Write-DevicePresetOnDevice([string]$Device, [string]$PackageName) {
  $preset = Get-DeviceRolePreset -Device $Device
  Write-Info "Writing device preset for ${Device}: role=$($preset.Role), label=$($preset.Label)"

  $tmp = Join-Path $env:TEMP "device-preset-$Device.properties"
  @(
    "role=$($preset.Role)",
    "label=$($preset.Label)",
    "adbSerial=$Device"
  ) | Set-Content -LiteralPath $tmp -Encoding ASCII

  & adb -s $Device push $tmp /data/local/tmp/device-preset.properties | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    Write-Warning "Could not push preset file to $Device."
    return
  }

  & adb -s $Device shell run-as $PackageName sh -c "mkdir -p files && cp /data/local/tmp/device-preset.properties files/device-preset.properties" | Out-Null
  if ($LASTEXITCODE -ne 0) {
    Write-Warning "Could not copy preset file into app storage on $Device. Is this a debuggable build and installed package?"
  }

  & adb -s $Device shell rm /data/local/tmp/device-preset.properties | Out-Null
  Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}

function Grant-AppPermissionsOnDevice([string]$Device, [string]$PackageName) {
  Write-Info "Granting runtime permissions for $PackageName on $Device"

  $permissions = @(
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.BLUETOOTH_SCAN',
    'android.permission.BLUETOOTH_ADVERTISE',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.NEARBY_WIFI_DEVICES'
  )

  foreach ($permission in $permissions) {
    $output = & adb -s $Device shell pm grant $PackageName $permission 2>&1
    if ($LASTEXITCODE -eq 0) {
      Write-Info "Granted $permission"
    } else {
      Write-Warning "Could not grant $permission on $Device. Android may not expose it as a grantable runtime permission for this OS/API/app install. $output"
    }
  }

  # Best-effort AppOps unlocks for Android versions that gate nearby discovery behind app ops.
  # Names differ by Android version/OEM, so failures are intentionally non-fatal.
  $appOps = @(
    'android:fine_location',
    'android:coarse_location',
    'android:bluetooth_scan',
    'android:bluetooth_advertise',
    'android:bluetooth_connect',
    'android:nearby_wifi_devices'
  )

  foreach ($op in $appOps) {
    $output = & adb -s $Device shell appops set $PackageName $op allow 2>&1
    if ($LASTEXITCODE -eq 0) {
      Write-Info "Allowed appop $op"
    } else {
      Write-Warning "Could not set appop $op on $Device. $output"
    }
  }
}

function Start-AppOnDevice([string]$Device, [string]$PackageName) {
  Write-Info "Launching $PackageName on $Device"

  # Stop a stale process first so the freshly installed debug build starts cleanly.
  & adb -s $Device shell am force-stop $PackageName | Out-Null

  # Use the launcher intent instead of a hard-coded Activity. This survives package/activity refactors.
  & adb -s $Device shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Null

  if ($LASTEXITCODE -ne 0) {
    throw "Failed to launch $PackageName on $Device."
  }
}

Push-Location $ProjectRoot
try {
  if (-not $SkipBuild) {
    $gradleArgs = Resolve-GradleCommand
    $gradleExe = $gradleArgs[0]
    $gradleTask = $gradleArgs[1]

    if ($Rebuild) {
      Write-Info 'Cleaning debug build output.'
      & $gradleExe :app:clean
    }

    Write-Info 'Building debug APK.'
    & $gradleExe $gradleTask
  }

  $apkPath = Get-DebugApkPath
  $devices = Get-ConnectedDevices

  if (-not $devices -or $devices.Count -eq 0) {
    throw 'No connected devices found. Check `adb devices`.'
  }

  Write-Info "Installing $apkPath to $($devices.Count) device(s)."
  foreach ($device in $devices) {
    Install-AppOnDevice -Device $device -PackageName $PackageName -ApkPath $apkPath -ForceUninstall:$ForceUninstall.IsPresent
    Write-DevicePresetOnDevice -Device $device -PackageName $PackageName
    Grant-AppPermissionsOnDevice -Device $device -PackageName $PackageName
    Start-AppOnDevice -Device $device -PackageName $PackageName
  }
}
finally {
  Pop-Location
}