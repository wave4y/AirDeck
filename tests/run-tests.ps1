param([string] $JavaHome = "$env:LOCALAPPDATA\Programs\Android Studio\jbr")
$ErrorActionPreference = 'Stop'
$testRoot = $PSScriptRoot
$projectRoot = Split-Path $testRoot -Parent
$classDir = Join-Path $testRoot '.classes'
New-Item -ItemType Directory -Path $classDir -Force | Out-Null
$compiler = Join-Path $JavaHome 'bin\javac.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
& $compiler -encoding UTF-8 -d $classDir (Join-Path $projectRoot 'app\src\main\java\com\airdeck\hid\HidReports.java') (Join-Path $testRoot 'HidReportsTest.java') (Join-Path $testRoot 'CompareDescriptor.java')
if ($LASTEXITCODE -ne 0) { throw 'Protocol tests failed to compile.' }
& $java -cp $classDir HidReportsTest
if ($LASTEXITCODE -ne 0) { throw 'Protocol tests failed.' }
& $compiler -encoding UTF-8 -d $classDir (Join-Path $projectRoot 'app\src\main\java\com\airdeck\hid\ReconnectPolicy.java') (Join-Path $projectRoot 'app\src\main\java\com\airdeck\hid\HidProtocolDiagnostics.java') (Join-Path $testRoot 'HidConnectionPolicyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Connection policy tests failed to compile.' }
& $java -cp $classDir com.airdeck.hid.HidConnectionPolicyTest
if ($LASTEXITCODE -ne 0) { throw 'Connection policy tests failed.' }
& $compiler -encoding UTF-8 -d $classDir (Join-Path $projectRoot 'app\src\main\java\com\airdeck\hid\GamepadLayoutConfig.java') (Join-Path $testRoot 'GamepadLayoutConfigTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Gamepad layout tests failed to compile.' }
& $java -cp $classDir com.airdeck.hid.GamepadLayoutConfigTest
if ($LASTEXITCODE -ne 0) { throw 'Gamepad layout tests failed.' }
& $compiler -encoding UTF-8 -d $classDir (Join-Path $projectRoot 'app\src\main\java\com\airdeck\hid\KeyboardPreset.java') (Join-Path $projectRoot 'app\src\main\java\com\airdeck\hid\KeyboardLayout.java') (Join-Path $testRoot 'KeyboardLayoutTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Keyboard layout tests failed to compile.' }
& $java -cp $classDir com.airdeck.hid.KeyboardLayoutTest
if ($LASTEXITCODE -ne 0) { throw 'Keyboard layout tests failed.' }
& $compiler -encoding UTF-8 -d $classDir (Join-Path $projectRoot 'app\src\main\java\com\airdeck\hid\GamepadPreset.java') (Join-Path $testRoot 'GamepadPresetTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Gamepad preset tests failed to compile.' }
& $java -cp $classDir com.airdeck.hid.GamepadPresetTest
if ($LASTEXITCODE -ne 0) { throw 'Gamepad preset tests failed.' }
