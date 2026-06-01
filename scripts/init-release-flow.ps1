[CmdletBinding()]
param(
  [string]$Workflow = "build-apk.yml",
  [string]$Branch = "main",
  [string]$ArtifactName = "app-release-apk",
  [string]$DownloadRoot = ".build-outputs/releases",
  [string]$Tag,
  [string]$Title,
  [string]$Notes,
  [int]$TimeoutSeconds = 1800
)

$ErrorActionPreference = 'Stop'

function Write-Info([string]$Message) {
  Write-Host "[release-flow] $Message"
}

function Assert-Command([string]$Name) {
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "Required command '$Name' was not found in PATH."
  }
}

function Get-VersionInfoFromGradle {
  param([string]$GradleFile)

  if (-not (Test-Path -LiteralPath $GradleFile)) {
    throw "Missing file: $GradleFile"
  }

  $content = Get-Content -LiteralPath $GradleFile -Raw
  $versionNameMatch = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
  $versionCodeMatch = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')

  if (-not $versionNameMatch.Success) { throw "Could not parse versionName from $GradleFile" }
  if (-not $versionCodeMatch.Success) { throw "Could not parse versionCode from $GradleFile" }

  [pscustomobject]@{
    VersionName = $versionNameMatch.Groups[1].Value
    VersionCode = [int]$versionCodeMatch.Groups[1].Value
  }
}

Assert-Command gh
Assert-Command git

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $projectRoot

$null = gh auth status

$version = Get-VersionInfoFromGradle -GradleFile (Join-Path $projectRoot "app/build.gradle.kts")

if ([string]::IsNullOrWhiteSpace($Tag)) {
  $Tag = "v$($version.VersionName)"
}
if ([string]::IsNullOrWhiteSpace($Title)) {
  $Title = $Tag
}
if ([string]::IsNullOrWhiteSpace($Notes)) {
  $Notes = "versionCode: $($version.VersionCode)`nversionName: $($version.VersionName)`nBuilt from GitHub Actions workflow '$Workflow'."
}

$headSha = (git rev-parse HEAD).Trim()
Write-Info "HEAD SHA: $headSha"
Write-Info "Triggering workflow '$Workflow' on '$Branch'"
gh workflow run $Workflow --ref $Branch | Out-Null

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$runId = $null

while ((Get-Date) -lt $deadline) {
  $runsJson = gh run list --workflow $Workflow --branch $Branch --limit 20 --json databaseId,headSha,status,createdAt,event
  $runs = $runsJson | ConvertFrom-Json

  $candidate = $runs |
    Where-Object { $_.headSha -eq $headSha } |
    Sort-Object -Property createdAt -Descending |
    Select-Object -First 1

  if ($candidate) {
    $runId = $candidate.databaseId
    break
  }

  Start-Sleep -Seconds 3
}

if (-not $runId) {
  throw "Unable to locate workflow run for SHA $headSha"
}

Write-Info "Watching run: $runId"
gh run watch $runId --exit-status

$downloadDir = Join-Path $DownloadRoot $Tag
if (Test-Path -LiteralPath $downloadDir) {
  Remove-Item -LiteralPath $downloadDir -Recurse -Force
}
New-Item -ItemType Directory -Path $downloadDir -Force | Out-Null

Write-Info "Downloading artifact '$ArtifactName'"
gh run download $runId -n $ArtifactName -D $downloadDir

$apk = Get-ChildItem -LiteralPath $downloadDir -Filter *.apk | Select-Object -First 1
if (-not $apk) {
  throw "No APK found in $downloadDir"
}

$releaseExists = $true
try {
  gh release view $Tag | Out-Null
} catch {
  $releaseExists = $false
}

if ($releaseExists) {
  Write-Info "Release '$Tag' exists. Uploading/replacing asset."
  gh release upload $Tag $apk.FullName --clobber
} else {
  Write-Info "Creating release '$Tag'"
  gh release create $Tag $apk.FullName --title $Title --notes $Notes
}

Write-Info "Release flow complete."
Write-Info "Run: https://github.com/Areo-RGB/game-app/actions/runs/$runId"
