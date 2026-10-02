param(
    [string] $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string] $JavaHome = "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    [string] $Target,
    [switch] $Run
)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$testBuildDir = Join-Path $projectDir '.build\android-tests'
$targetClasses = Join-Path $projectDir '.build\classes'
$targetJar = Join-Path $projectDir '.build\classes.jar'
$keystore = Join-Path $projectDir '.build\debug.keystore'
$toolDir = Join-Path $AndroidSdk 'build-tools\35.0.0'
$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$legacyCompiler = Join-Path $env:ProgramFiles 'Java\jdk1.8.0_202\bin\javac.exe'
if (Test-Path -LiteralPath $legacyCompiler) { $javac = $legacyCompiler }
$jar = Join-Path $JavaHome 'bin\jar.exe'
$adb = Join-Path $AndroidSdk 'platform-tools\adb.exe'
foreach ($required in @($java, $javac, $jar, $androidJar, $targetJar, $keystore,
    (Join-Path $targetClasses 'com\airdeck\hid\GamepadView.class'),
    (Join-Path $toolDir 'aapt2.exe'), (Join-Path $toolDir 'zipalign.exe'),
    (Join-Path $toolDir 'lib\d8.jar'), (Join-Path $toolDir 'lib\apksigner.jar'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing dependency: $required. Build the app with build.ps1 first." }
}
if ($Run -and [string]::IsNullOrWhiteSpace($Target)) { throw 'Use -Run -Target <ADB serial>; no device is selected by default.' }
if ($Run -and -not (Test-Path -LiteralPath $adb)) { throw "ADB not found: $adb" }
function Run-Tool([string] $Program, [string[]] $ToolArgs) {
    & $Program @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Test build tool failed with exit code ${LASTEXITCODE}: $Program" }
}
# Delete only this test harness's intermediates; never touch target app classes or preferences.
$canonicalTestRoot = [System.IO.Path]::GetFullPath($testBuildDir).TrimEnd('\') + '\'
foreach ($folder in @('classes', 'dex')) {
    $path = [System.IO.Path]::GetFullPath((Join-Path $testBuildDir $folder))
    if (-not $path.StartsWith($canonicalTestRoot, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test intermediate path.' }
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}
foreach ($folder in @($testBuildDir, (Join-Path $testBuildDir 'classes'), (Join-Path $testBuildDir 'dex'))) {
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
}
$manifestPath = Join-Path $testBuildDir 'AndroidManifest.xml'
$manifest = @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.airdeck.hid.layouttests">
  <application android:label="AirDeck layout tests" android:debuggable="true" android:allowBackup="false" />
  <instrumentation android:name="com.airdeck.hid.layouttests.GamepadEditorInstrumentation" android:targetPackage="com.airdeck.hid" android:functionalTest="false" android:handleProfiling="false" />
</manifest>
'@
[System.IO.File]::WriteAllText($manifestPath, $manifest, (New-Object System.Text.UTF8Encoding($false)))
$resourceApk = Join-Path $testBuildDir 'resources.apk'
Run-Tool (Join-Path $toolDir 'aapt2.exe') @('link', '-o', $resourceApk, '--manifest', $manifestPath, '-I', $androidJar,
    '--min-sdk-version', '28', '--target-sdk-version', '35', '--version-code', '1', '--version-name', '1.0')
Run-Tool $javac @('-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-source', '8', '-target', '8',
    '-classpath', "$androidJar;$targetClasses", '-d', (Join-Path $testBuildDir 'classes'),
    (Join-Path $PSScriptRoot 'android\GamepadEditorInstrumentation.java'))
$testJar = Join-Path $testBuildDir 'instrumentation.jar'
Run-Tool $jar @('cf', $testJar, '-C', (Join-Path $testBuildDir 'classes'), '.')
# The app jar is a compile-only dependency. Only the instrumentation jar becomes test APK dex.
Run-Tool $java @('-cp', (Join-Path $toolDir 'lib\d8.jar'), 'com.android.tools.r8.D8', '--min-api', '28',
    '--lib', $androidJar, '--classpath', $targetJar, '--output', (Join-Path $testBuildDir 'dex'), $testJar)
$unsignedApk = Join-Path $testBuildDir 'unsigned.apk'
$alignedApk = Join-Path $testBuildDir 'aligned.apk'
$outputApk = Join-Path $testBuildDir 'airdeck-layout-tests.apk'
Copy-Item -LiteralPath $resourceApk -Destination $unsignedApk -Force
Run-Tool $jar @('uf', $unsignedApk, '-C', (Join-Path $testBuildDir 'dex'), 'classes.dex')
Run-Tool (Join-Path $toolDir 'zipalign.exe') @('-f', '4', $unsignedApk, $alignedApk)
Run-Tool $java @('-jar', (Join-Path $toolDir 'lib\apksigner.jar'), 'sign', '--ks', $keystore, '--ks-key-alias',
    'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $outputApk, $alignedApk)
Run-Tool $java @('-jar', (Join-Path $toolDir 'lib\apksigner.jar'), 'verify', '--verbose', $outputApk)
Write-Output "Built instrumentation APK: $outputApk"
if ($Run) {
    Run-Tool $adb @('-s', $Target, 'install', '--no-incremental', '-r', $outputApk)
    $result = & $adb -s $Target shell am instrument -r -w 'com.airdeck.hid.layouttests/com.airdeck.hid.layouttests.GamepadEditorInstrumentation'
    $exitCode = $LASTEXITCODE
    $result | Write-Output
    if ($exitCode -ne 0 -or ($result -join "`n") -notmatch 'INSTRUMENTATION_RESULT: result=PASS') {
        throw 'Android editor instrumentation failed. Inspect the output above.'
    }
}
