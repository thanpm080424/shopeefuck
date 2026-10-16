param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath
)
$ErrorActionPreference = 'Stop'

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$workspace = Split-Path -Parent $PSScriptRoot
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending
$aapt = $buildTools | ForEach-Object { Join-Path $_.FullName 'aapt.exe' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$apksigner = $buildTools | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $aapt -or -not $apksigner) { throw "Android SDK build-tools not found under $sdk." }

$tempApk = Join-Path $env:TEMP 'hkpick_driver_publish.apk'
Copy-Item -LiteralPath $apk -Destination $tempApk -Force
try {
    $badging = (& $aapt dump badging $tempApk 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw "Could not inspect APK: $badging" }
    $packageMatch = [regex]::Match($badging, "(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'")
    $minimumMatch = [regex]::Match($badging, "(?m)^sdkVersion:'([0-9]+)'")
    if (-not $packageMatch.Success -or $packageMatch.Groups[1].Value -ne 'com.samsung.health.addon') {
        throw 'Selected file is not the expected HKPick driver APK.'
    }
    if (-not $minimumMatch.Success) { throw 'APK minimum Android API is missing.' }
    $verify = & $apksigner verify --verbose $tempApk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $($verify -join ' ')" }
    $cert = (& $apksigner verify --print-certs $tempApk 2>&1) -join [Environment]::NewLine
    $certMatch = [regex]::Match($cert, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)')
    if (-not $certMatch.Success) { throw 'APK signer certificate digest was not found.' }
    $certDigest = $certMatch.Groups[1].Value.Replace(':', '').ToLowerInvariant()
} finally {
    Remove-Item -LiteralPath $tempApk -Force -ErrorAction SilentlyContinue
}

$functionsPackage = Join-Path $workspace 'functions\package.json'
if (-not (Test-Path (Join-Path $workspace 'functions\node_modules\firebase-admin'))) {
    Push-Location (Join-Path $workspace 'functions')
    try { npm install } finally { Pop-Location }
}
Push-Location $workspace
try {
    $env:NODE_PATH = Join-Path $workspace 'functions\node_modules'
    node 'tools/publish_driver_release.cjs' $apk $packageMatch.Groups[3].Value `
        $packageMatch.Groups[2].Value $minimumMatch.Groups[1].Value $certDigest
    if ($LASTEXITCODE -ne 0) { throw 'Publishing the release failed.' }
} finally { 
    $env:NODE_PATH = $null
    Pop-Location 
}
