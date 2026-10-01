param(
    [string] $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string] $JavaHome = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$buildDir = Join-Path $projectDir '.build'
$outputDir = Join-Path $projectDir 'app\build\outputs\apk\debug'
$toolDir = Join-Path $AndroidSdk 'build-tools\35.0.0'
$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
$java = Join-Path $JavaHome 'bin\java.exe'
$javac = Join-Path $JavaHome 'bin\javac.exe'
# JDK 8 emits compatible Android bytecode and avoids JDK 17 zipfs cleanup issues on Windows.
$legacyCompiler = Join-Path $env:ProgramFiles 'Java\jdk1.8.0_202\bin\javac.exe'
if (Test-Path -LiteralPath $legacyCompiler) { $javac = $legacyCompiler }
$jar = Join-Path $JavaHome 'bin\jar.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
foreach ($required in @($java, $javac, $jar, $keytool, $androidJar, (Join-Path $toolDir 'aapt2.exe'), (Join-Path $toolDir 'zipalign.exe'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required Android build tool not found: $required" }
}
function Run-Tool([string] $Program, [string[]] $ToolArgs) {
    & $Program @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed with exit code ${LASTEXITCODE}: $Program" }
}
# Reset only intermediate folders inside this project's .build directory.
$canonicalBuildDir = [System.IO.Path]::GetFullPath($buildDir).TrimEnd('\') + '\'
foreach ($folder in @('generated', 'classes', 'dex')) {
    $intermediateDir = [System.IO.Path]::GetFullPath((Join-Path $buildDir $folder))
    if (-not $intermediateDir.StartsWith($canonicalBuildDir, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe intermediate build path.' }
    if (Test-Path -LiteralPath $intermediateDir) { Remove-Item -LiteralPath $intermediateDir -Recurse -Force }
}
foreach ($dir in @($buildDir, $outputDir, (Join-Path $buildDir 'generated'), (Join-Path $buildDir 'classes'), (Join-Path $buildDir 'dex'))) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}
# aapt2 operates without Maven, Gradle, or network access. Keep the normal manifest usable by AGP.
[xml] $manifestXml = Get-Content -LiteralPath (Join-Path $projectDir 'app\src\main\AndroidManifest.xml') -Raw
$manifestXml.manifest.SetAttribute('package', 'com.airdeck.hid')
$manifestXml.manifest.application.SetAttribute('debuggable', 'http://schemas.android.com/apk/res/android', 'true') | Out-Null
$manifestFile = Join-Path $buildDir 'AndroidManifest.xml'
$manifestXml.Save($manifestFile)
$compiledResources = Join-Path $buildDir 'resources.zip'
$resourceApk = Join-Path $buildDir 'resources.apk'
Run-Tool (Join-Path $toolDir 'aapt2.exe') @('compile', '--dir', (Join-Path $projectDir 'app\src\main\res'), '-o', $compiledResources)
Run-Tool (Join-Path $toolDir 'aapt2.exe') @('link', '-o', $resourceApk, '--manifest', $manifestFile, '-I', $androidJar, '--java', (Join-Path $buildDir 'generated'), '--min-sdk-version', '28', '--target-sdk-version', '35', '--version-code', '4', '--version-name', '1.3.0', $compiledResources)
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectDir 'app\src\main\java'), (Join-Path $buildDir 'generated') -Filter '*.java' -Recurse -File | Select-Object -ExpandProperty FullName)
if ($sourceFiles.Count -eq 0) { throw 'No Java source files found.' }
$compilerArguments = @('-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', $androidJar, '-d', (Join-Path $buildDir 'classes')) + $sourceFiles
Run-Tool $javac $compilerArguments
$classesJar = Join-Path $buildDir 'classes.jar'
Run-Tool $jar @('cf', $classesJar, '-C', (Join-Path $buildDir 'classes'), '.')
Run-Tool $java @('-cp', (Join-Path $toolDir 'lib\d8.jar'), 'com.android.tools.r8.D8', '--min-api', '28', '--lib', $androidJar, '--output', (Join-Path $buildDir 'dex'), $classesJar)
$unsignedApk = Join-Path $buildDir 'unsigned.apk'
$alignedApk = Join-Path $buildDir 'aligned.apk'
Copy-Item -LiteralPath $resourceApk -Destination $unsignedApk -Force
Run-Tool $jar @('uf', $unsignedApk, '-C', (Join-Path $buildDir 'dex'), 'classes.dex')
Run-Tool (Join-Path $toolDir 'zipalign.exe') @('-f', '4', $unsignedApk, $alignedApk)
$keystore = Join-Path $projectDir '.build\debug.keystore'
if (-not (Test-Path -LiteralPath $keystore)) {
    Run-Tool $keytool @('-genkeypair', '-keystore', $keystore, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-dname', 'CN=AirDeck Debug,O=AirDeck,C=CN', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-noprompt')
}
$outputApk = Join-Path $outputDir 'app-debug.apk'
$apksigner = Join-Path $toolDir 'lib\apksigner.jar'
Run-Tool $java @('-jar', $apksigner, 'sign', '--ks', $keystore, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $outputApk, $alignedApk)
Run-Tool $java @('-jar', $apksigner, 'verify', '--verbose', $outputApk)
Write-Output "Built: $outputApk"
