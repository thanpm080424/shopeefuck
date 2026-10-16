$ErrorActionPreference = 'Stop'

$driverProject = Split-Path -Parent $PSScriptRoot
$signingRoot = Join-Path $env:USERPROFILE '.android\hkpick-signing'
$keystorePath = Join-Path $signingRoot 'hkpick-release.p12'
$encryptedPasswordPath = Join-Path $signingRoot 'password.dpapi'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$desktopPath = [Environment]::GetFolderPath('Desktop')
$outputDir = Join-Path $desktopPath "HKPick-release-$timestamp"
$symbolsRoot = Join-Path $signingRoot 'symbols'

foreach ($requiredPath in @($keystorePath, $encryptedPasswordPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Required release input is missing: $requiredPath"
    }
}

$securePassword = Get-Content -LiteralPath $encryptedPasswordPath -Raw | ConvertTo-SecureString
$env:HKPICK_SIGNING_KEYSTORE = $keystorePath
$env:HKPICK_SIGNING_ALIAS = 'hkp-release'
$env:HKPICK_SIGNING_PASSWORD = [System.Net.NetworkCredential]::new('', $securePassword).Password

try {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null

    # Keep a matching symbol set for every release. Reusing one directory would
    # overwrite app.android-*.symbols on the next build and break old stack traces.
    $symbolDir = Join-Path $symbolsRoot "driver-$timestamp"
    New-Item -ItemType Directory -Path $symbolDir -Force | Out-Null

    Push-Location (Join-Path $driverProject 'android')
    try {
        & .\gradlew.bat --stop | Out-Null
    } finally {
        Pop-Location
    }

    Push-Location $driverProject
    try {
        & flutter build apk --release --obfuscate "--split-debug-info=$symbolDir"
        if ($LASTEXITCODE -ne 0) {
            throw "Flutter release build failed for the driver app (exit $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }

    $builtApk = Join-Path $driverProject 'build\app\outputs\flutter-apk\app-release.apk'
    if (-not (Test-Path -LiteralPath $builtApk)) {
        throw "Expected APK was not created: $builtApk"
    }
    Copy-Item -LiteralPath $builtApk -Destination (Join-Path $outputDir 'hkpick_driver_release.apk') -Force

    Write-Output "Driver release APK: $(Join-Path $outputDir 'hkpick_driver_release.apk')"
    Write-Output "Private Dart symbol maps for this release: $symbolDir"
} finally {
    $env:HKPICK_SIGNING_KEYSTORE = $null
    $env:HKPICK_SIGNING_ALIAS = $null
    $env:HKPICK_SIGNING_PASSWORD = $null
    $securePassword = $null
}
