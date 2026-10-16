$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
$installerProject = Join-Path $workspace 'phone_installer'
$desktop = [Environment]::GetFolderPath('Desktop')
$assets = Join-Path $installerProject 'app\src\main\assets'
$signingRoot = Join-Path $env:USERPROFILE '.android\hkpick-signing'
$keystorePath = Join-Path $signingRoot 'hkpick-release.p12'
$encryptedPasswordPath = Join-Path $signingRoot 'password.dpapi'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outputDir = Join-Path $desktop "HKPick-installer-$timestamp"
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

foreach ($requiredPath in @($keystorePath, $encryptedPasswordPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required release-signing input is missing: $requiredPath"
    }
}

$driverCandidates = Get-ChildItem -LiteralPath $desktop -Directory -Filter 'HKPick-release-*' |
    ForEach-Object { Join-Path $_.FullName 'hkpick_driver_release.apk' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    ForEach-Object { Get-Item -LiteralPath $_ } |
    Sort-Object LastWriteTime -Descending

if (-not $driverCandidates) {
    throw 'No signed driver release APK found in Desktop\HKPick-release-* folders.'
}
$driverApk = $driverCandidates[0].FullName
$tempDriverApk = Join-Path $env:TEMP 'hkpick_driver_inspect.apk'
Copy-Item -LiteralPath $driverApk -Destination $tempDriverApk -Force

$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending
$aapt = ($buildTools | ForEach-Object { Join-Path $_.FullName 'aapt.exe' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1)
$apksigner = ($buildTools | ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1)
if (-not $aapt -or -not $apksigner) {
    throw "Android SDK build-tools with aapt and apksigner were not found under $sdk."
}
if (-not (Test-Path -LiteralPath $sdk)) {
    throw "Android SDK was not found at $sdk."
}

$sdkPathForGradle = $sdk.Replace('\', '/')
Set-Content -LiteralPath (Join-Path $installerProject 'local.properties') -Value "sdk.dir=$sdkPathForGradle" -NoNewline -Encoding ascii

$badgingLines = & $aapt dump badging $tempDriverApk 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the driver APK manifest: $($badgingLines -join ' ')"
}
$badging = $badgingLines -join [Environment]::NewLine
$packageMatch = [regex]::Match($badging, "(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'")
$minimumMatch = [regex]::Match($badging, "(?m)^sdkVersion:'([0-9]+)'")
if (-not $packageMatch.Success) {
    throw 'The selected APK is missing package/version metadata.'
}
if ($packageMatch.Groups[1].Value -ne 'com.samsung.health.addon') {
    throw "Wrong APK package: $($packageMatch.Groups[1].Value). Expected com.samsung.health.addon."
}
if (-not $minimumMatch.Success) {
    throw 'The driver APK minimum Android version metadata is missing.'
}
$requiredApi = [int]$minimumMatch.Groups[1].Value

$verifyLines = & $apksigner verify --verbose $tempDriverApk 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "The driver APK signature did not verify: $($verifyLines -join ' ')"
}
$certLines = & $apksigner verify --print-certs $tempDriverApk 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Could not read the driver APK signing certificate: $($certLines -join ' ')"
}
$certText = $certLines -join [Environment]::NewLine
$certMatch = [regex]::Match($certText, 'Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)')
if (-not $certMatch.Success) {
    throw 'Could not find the driver signer certificate SHA-256 digest.'
}
$certDigest = $certMatch.Groups[1].Value.Replace(':', '').ToLowerInvariant()

New-Item -ItemType Directory -Path $assets -Force | Out-Null
Set-Content -LiteralPath (Join-Path $assets 'driver.cert.sha256') -Value $certDigest -NoNewline -Encoding ascii
Remove-Item -LiteralPath (Join-Path $assets 'driver.apk') -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $assets 'driver.sha256') -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $assets 'driver.version') -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $tempDriverApk -Force -ErrorAction SilentlyContinue

$securePassword = Get-Content -LiteralPath $encryptedPasswordPath -Raw | ConvertTo-SecureString
$env:HKPICK_SIGNING_KEYSTORE = $keystorePath
$env:HKPICK_SIGNING_ALIAS = 'hkp-release'
$env:HKPICK_SIGNING_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

try {
    Push-Location $installerProject
    try {
        & .\gradlew.bat --no-daemon :app:assembleRelease
        if ($LASTEXITCODE -ne 0) {
            throw "Phone installer release build failed (exit $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }

    $builtApk = Join-Path $installerProject 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $builtApk)) {
        throw "Expected installer APK was not created: $builtApk"
    }
    $verifyInstaller = & $apksigner verify --verbose $builtApk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Installer APK signature did not verify: $($verifyInstaller -join ' ')"
    }
    $installerBadging = (& $aapt dump badging $builtApk 2>&1) -join [Environment]::NewLine
    if ($installerBadging -notmatch "package: name='com\.hkpick\.phoneinstaller'") {
        throw 'Built APK is not the HKPick phone installer package.'
    }

    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    $deliveryApk = Join-Path $outputDir 'HKPick_Phone_Installer.apk'
    Copy-Item -LiteralPath $builtApk -Destination $deliveryApk -Force
    $deliveryHash = (Get-FileHash -LiteralPath $deliveryApk -Algorithm SHA256).Hash

    Write-Output "Driver bundled: No (downloaded after device license verification)"
    Write-Output "Pinned driver signer SHA256: $certDigest"
    Write-Output "Installer APK: $deliveryApk"
    Write-Output "SHA256: $deliveryHash"
} finally {
    $env:HKPICK_SIGNING_KEYSTORE = $null
    $env:HKPICK_SIGNING_ALIAS = $null
    $env:HKPICK_SIGNING_PASSWORD = $null
    $securePassword = $null
}
