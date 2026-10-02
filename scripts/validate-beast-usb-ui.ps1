#!/usr/bin/env pwsh
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^emulator-\d+$')]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$appApk = Join-Path $root "app/build/outputs/apk/stable/debug/app-stable-debug.apk"
$testApk = Join-Path $root "app/build/outputs/apk/androidTest/stable/debug/app-stable-debug-androidTest.apk"
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if (!(Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$adbArgs = @()
if ($Serial) { $adbArgs += @('-s', $Serial) }

Push-Location $root
try {
    & .\gradlew.bat ':app:testStableDebugUnitTest' ':app:assembleStableDebug' ':app:assembleStableDebugAndroidTest' '--offline' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Gradle assemble failed: $LASTEXITCODE" }
    if (!(Test-Path $appApk) -or !(Test-Path $testApk)) { throw 'Expected stable app and instrumentation APKs were not produced.' }
    & $adb @adbArgs install -r $appApk
    if ($LASTEXITCODE -ne 0) { throw "App install failed: $LASTEXITCODE" }
    & $adb @adbArgs install -r $testApk
    if ($LASTEXITCODE -ne 0) { throw "Instrumentation install failed: $LASTEXITCODE" }

    & $adb @adbArgs emu multidisplay add 1 1920 1080 160 0
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create emulator secondary display.' }
    & $adb @adbArgs shell settings put secure immersive_mode_confirmations confirmed
    foreach ($setting in @('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale')) {
        & $adb @adbArgs shell settings put global $setting 0
    }
    $runner = 'com.lateral.test/androidx.test.runner.AndroidJUnitRunner'
    $result = & $adb @adbArgs shell am instrument -w -e class 'com.lateral.beast.BeastUsbBootstrapUiAndroidTest' $runner
    $result | Write-Output
    if (($result -join "`n") -notmatch 'OK \(1 test\)' -or ($result -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED') { throw 'Beast UI instrumentation did not pass.' }
    if ($LASTEXITCODE -ne 0) { throw "Beast UI regression failed: $LASTEXITCODE" }
    $result = & $adb @adbArgs shell am instrument -w -e class 'com.lateral.privileged.UsbHelperLifecycleAndroidTest#detachedBootstrapSurvivesStdinEofAndOwnerCancellationCleansUp' $runner
    $result | Write-Output
    if (($result -join "`n") -notmatch 'OK \(1 test\)' -or ($result -join "`n") -match 'FAILURES|INSTRUMENTATION_FAILED') { throw 'Detached USB instrumentation did not pass.' }
    if ($LASTEXITCODE -ne 0) { throw "Detached USB owner regression failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
