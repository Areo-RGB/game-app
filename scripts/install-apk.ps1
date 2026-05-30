#Requires -Version 5.1
<#
.SYNOPSIS
Builds the debug APK and installs it on every connected Android device.

.DESCRIPTION
Runs the Gradle debug assemble task, finds the generated APK, and installs it
on each adb device that is currently in the "device" state.

Use -Launch to start the app on each connected device after installation.
#>

[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$BuildTask = ":app:assembleDebug",
    [string]$ApplicationId = $null,
    [switch]$SkipBuild,
    [switch]$Launch
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory)]
        [string]$FilePath,

        [Parameter(Mandatory)]
        [string[]]$Arguments,

        [Parameter(Mandatory)]
        [string]$Action
    )

    Write-Host "==> $Action"
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed with exit code $LASTEXITCODE."
    }
}

function Get-ApplicationIdFromGradle {
    param(
        [Parameter(Mandatory)]
        [string]$Root
    )

    $buildFile = Join-Path $Root 'app/build.gradle.kts'
    if (-not (Test-Path $buildFile)) {
        throw "Could not find $buildFile."
    }

    $content = Get-Content -Path $buildFile -Raw
    if ($content -match 'applicationId\s*=\s*"(?<id>[^"]+)"') {
        return $Matches.id
    }

    throw "Could not determine the applicationId from $buildFile. Pass -ApplicationId explicitly."
}

function Get-ConnectedDeviceSerials {
    param(
        [Parameter(Mandatory)]
        [string]$AdbPath
    )

    $output = & $AdbPath devices
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to query connected adb devices.'
    }

    $serials = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($output | Select-Object -Skip 1)) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }

        if ($line -match '^(?<serial>\S+)\s+(?<state>\S+)$') {
            switch ($Matches.state) {
                'device' {
                    [void]$serials.Add($Matches.serial)
                }
                default {
                    Write-Warning "Skipping $($Matches.serial) ($($Matches.state))."
                }
            }
        }
    }

    return $serials
}

$projectRoot = (Resolve-Path $ProjectRoot).Path
$gradlew = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    throw "Could not find gradlew.bat at $gradlew."
}

$adbCommand = Get-Command adb -ErrorAction Stop
$adbPath = $adbCommand.Source
if ([string]::IsNullOrWhiteSpace($adbPath)) {
    $adbPath = $adbCommand.Path
}
if ([string]::IsNullOrWhiteSpace($adbPath)) {
    $adbPath = 'adb'
}

if ([string]::IsNullOrWhiteSpace($ApplicationId)) {
    $ApplicationId = Get-ApplicationIdFromGradle -Root $projectRoot
}

$apkCandidates = @(
    (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'),
    (Join-Path $projectRoot '.build-outputs/app-debug.apk')
)

if (-not $SkipBuild) {
    Push-Location $projectRoot
    try {
        Invoke-NativeCommand -FilePath $gradlew -Arguments @($BuildTask) -Action "Building $BuildTask"
    }
    finally {
        Pop-Location
    }
}

$apkPath = $apkCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apkPath) {
    throw "APK not found. Looked in: $($apkCandidates -join ', ')."
}

$devices = Get-ConnectedDeviceSerials -AdbPath $adbPath
if (-not $devices -or $devices.Count -eq 0) {
    throw 'No connected adb devices in the "device" state were found.'
}

Write-Host "Using APK: $apkPath"
Write-Host "Application ID: $ApplicationId"
Write-Host "Connected devices: $($devices -join ', ')"

foreach ($serial in $devices) {
    Invoke-NativeCommand -FilePath $adbPath -Arguments @('-s', $serial, 'install', '-r', '-d', '-g', $apkPath) -Action "Installing on $serial"

    if ($Launch) {
        Invoke-NativeCommand -FilePath $adbPath -Arguments @(
            '-s',
            $serial,
            'shell',
            'monkey',
            '-p',
            $ApplicationId,
            '-c',
            'android.intent.category.LAUNCHER',
            '1'
        ) -Action "Launching $ApplicationId on $serial"
    }
}

Write-Host "Done. Installed to $($devices.Count) device(s)."
if ($Launch) {
    Write-Host 'Launch requested: the app was started on each connected device.'
}
