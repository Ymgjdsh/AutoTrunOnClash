param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -and (Test-Path -LiteralPath (Join-Path $projectRoot 'local.properties'))) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    if ($sdkLine) { $AndroidSdk = $sdkLine.Substring(8) }
}
if (-not $AndroidSdk -or -not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms/android-35/android.jar'))) {
    throw 'Please specify an Android SDK containing platform 35 with -AndroidSdk.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Please specify JDK 21 (recommended) or another Gradle 9.3 compatible JDK with -JavaHome.'
}
if (-not $BuildRoot) {
    $digest = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($projectRoot))).Replace('-', '').Substring(0, 12).ToLowerInvariant() }
    finally { $digest.Dispose() }
    $BuildRoot = Join-Path $env:USERPROFILE ".gradle/clash-auto-proxy/$hash"
}
$env:JAVA_HOME = $JavaHome
Set-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Value ('sdk.dir=' + $AndroidSdk.Replace([IO.Path]::DirectorySeparatorChar, [char]'/')) -Encoding ascii
Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') "-PexternalBuildDir=$BuildRoot" --no-daemon --console=plain assembleDebug testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed. See the Gradle output.' }
    $dist = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $versionMatch = Select-String -LiteralPath (Join-Path $projectRoot 'app/build.gradle') -Pattern "versionName\s+['\x22]([^'\x22]+)['\x22]"
    if (-not $versionMatch) { throw 'versionName missing in app/build.gradle' }
    $version = $versionMatch.Matches[0].Groups[1].Value
    $apkName = "ClashAutoProxy-$version-debug.apk"
    $apk = Join-Path $dist $apkName
    Copy-Item -LiteralPath (Join-Path $BuildRoot 'app/outputs/apk/debug/app-debug.apk') -Destination $apk -Force
    $sha = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath ($apk + '.sha256') -Value "$sha  $apkName" -Encoding ascii
    Write-Output "Verified APK: $apk"
    Write-Output "SHA-256: $sha"
} finally { Pop-Location }
