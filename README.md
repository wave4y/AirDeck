# AirDeck

把 Android 设备作为蓝牙 HID 输入设备，控制另一台 Android 设备。应用通过 `BluetoothHidDevice` 发送键盘、鼠标和通用游戏手柄报告，正常使用时接收端无需安装应用。

**版本：1.2.0（versionCode 3）。** 普通键盘保留完整 87 键 ANSI TKL；键鼠面板采用 70 键主区，加触控板上方 6 个编辑键和下方 4 个方向键，共 80 个唯一按键。支持修饰键真实按住、Shift 动态键帽及接收端锁定键状态反馈。验证范围见下文。

## 功能

- **键盘：** 87 键 ANSI TKL，包含功能键、主键区、编辑导航区和方向键；支持修饰键组合及英文文本发送。
- **触控板：** 单指移动、轻点左键、双指轻点右键、双指滚动、独立左右键和按住拖动。
- **键鼠同屏：** 70 键主区配合触控板周围的 6 个编辑键、4 个方向键；横屏左键盘右触控板，竖屏上触控板下键盘。
- **手柄：** PSP、PS5、Xbox、Switch 布局，多点触控；保存上次选择。
- **连接：** 已配对设备列表、配对引导、前台自动重连、手动断开和连接诊断。

应用使用原生 Java 和 Android SDK，无第三方运行时依赖，未声明互联网权限。输入通过本地蓝牙传输。

## 键盘布局与组合键

普通键盘保留完整 **87 键 ANSI TKL**：Esc、F1–F12、Print Screen / Scroll Lock / Pause、主键区、Insert / Home / Page Up / Delete / End / Page Down，以及独立方向键。TKL 不包含数字小键盘。

键鼠面板使用 **80 个唯一按键**：键盘主区 70 键；Insert、Home、Page Up、Delete、End、Page Down 共 6 键位于触控板上方；4 个方向键位于触控板下方。为给触控区域留出空间，键鼠面板移除右侧 Shift / Ctrl / Alt / Meta，以及 Print Screen / Scroll Lock / Pause。普通键盘的 87 键保持不变。

普通键盘保留左右 Shift、Ctrl、Alt、Meta，键鼠面板保留左侧这四个修饰键。所有修饰键均为真实按住/松开：手指按下即发送修饰键按下，抬起、滑出键帽或取消触摸时释放，不再通过点击切换为持续点亮。多点触控支持同时按住修饰键与普通按键，例如按住 Ctrl，再按 C，最后松开两者。

- 数字和符号键帽右上角以小号文字显示对应的 Shift 符号，如 `1` 键的 `!`、`[` 键的 `{`；按住 Shift 后，主标签仍切换为上档符号，字母主标签同步切换大小写。
- Caps Lock 状态来自接收端的键盘 LED 反馈，不在本地点击后猜测开关状态。没有反馈时，不把点击视为锁定成功。
- 字母大小写按 `Shift XOR Caps Lock` 显示：Caps 开启时再按住 Shift，字母显示小写；数字和标点仅受 Shift 影响。
- 普通键盘的 Scroll Lock 指示同样使用接收端 LED 反馈。切换面板、尺寸变化、旋转或控件移除时会释放本面板按住的输入。

键帽显示遵循美式布局，具体按键作用与接收端系统、输入法和应用有关。
### 竖屏键盘分区

普通键盘竖屏保留全部 87 键，按 9 行排列：

1. Esc 与 F1–F6。
2. F7–F12 与 Print Screen / Scroll Lock / Pause。
3. 接下来的 5 行为完整 ANSI 主键区。
4. 6 个编辑键独占一行。
5. 4 个方向键独占一行。

主键区宽度按 15 个标准键位单位分配，行高使用全部可用键盘高度。普通键盘横屏仍保持原来的 87 键 ANSI TKL 分区。

键鼠竖屏从上到下依次为：**6 个编辑键 → 触控板 → 鼠标左右键 → 4 个方向键 → 7 行主键盘**。主键盘将功能键分成两排，加上 5 行 ANSI 主键区，占约 58% 的可用控制区高度。键鼠横屏继续使用 70 键主区与触控板周围 10 键的 80 键分区。

这两个键盘页面在竖屏隐藏大连接卡片，保留右上角连接状态，左右边距缩为 12dp，为按键增加可用宽度。
## 横屏与手柄预设

触控板、键盘、键鼠和手柄页横屏时只显示控制区，应用标题与导航隐藏，系统栏进入沉浸模式。系统返回键或返回手势会回到**同一页的竖屏**；系统边缘手势可能临时显示系统栏。设备页保留正常导航。

在竖屏手柄页选择布局再进入横屏。默认 Xbox，上次选择会保存；切换模式或布局会释放按住的输入。

| 预设 | 控制区 | 面键位置：下 / 右 / 左 / 上 |
| --- | --- | --- |
| PSP | 单摇杆、方向键、L/R；无右摇杆、扳机、L3/R3 | × / ○ / □ / △ |
| PS5 | 对称双摇杆、方向键、L1/R1、L2/R2、摇杆按压 | × / ○ / □ / △ |
| Xbox | 错位双摇杆、方向键、LB/RB、LT/RT、摇杆按压 | A / B / X / Y |
| Switch | 错位双摇杆、方向键、L/R、ZL/ZR、摇杆按压 | B / A / Y / X |

这些预设仍使用通用 HID 手柄协议。布局和标签变化不交换面键的 HID 物理位置映射：例如 Switch 下方显示 B，右方显示 A，接收端按游戏自己的映射识别。游戏需支持通用外接手柄；必要时在游戏内配置按键。当前没有触屏映射或屏幕点击注入。

## 设备要求与使用

操控端需 Android 9 / API 28 或更高版本，并且系统固件支持蓝牙 HID Device 角色。仅满足系统版本要求不保证具备该能力。编译和目标 API 为 35。

1. 在操控端安装 AirDeck，打开蓝牙并允许应用请求的蓝牙权限。
2. 保持 AirDeck 在前台，进入“设备 → 配对新设备”，允许系统提示的蓝牙可见请求。
3. 在接收端系统蓝牙设置中搜索操控端，确认双方的配对请求。
4. 在 AirDeck 设备列表点击接收端，等待连接完成。
5. 先在接收端文本框测试英文字母和鼠标，再在支持手柄的游戏中验证手柄。

控制输入和自动重连以应用位于前台为前提。切换应用、锁屏或省电策略可能影响 HID 注册或连接。

### 系统与窗口适配

布局依据当前 View 的可用宽高计算，不按设备型号写死尺寸。键盘按标准键位比例布置，间距随显示密度换算，键帽字号根据按键宽高及文字长度调整；横屏使用传感器方向，允许正向与反向横屏。全屏控制区保留屏幕缺口安全区。

API 30 及以上使用 `WindowInsetsController` 隐藏和恢复系统栏，允许边缘手势暂时唤出；较旧系统保留兼容处理。实现参考 [Android 沉浸模式文档](https://developer.android.com/develop/ui/views/layout/immersive)。API 33 及以上接入原生 `OnBackInvokedCallback`，仅在全屏或非首页时处理应用内返回，优先退出全屏或返回首页。

针对目标 API 35 在 Android 15 上的边到边行为，普通页面合并系统栏、屏幕缺口与输入法的安全留白，避免内容被覆盖。行为依据见 [Android 15 窗口 Insets 变更](https://developer.android.com/about/versions/15/behavior-changes-15)。

这些适配规则不等于所有固件和窗口组合已经实测；小屏、大窗口、字号/密度变化与安全区的验证范围以下方测试结果为准。蓝牙功能仍取决于系统提供的 HID Device 能力。
### 中文与文本发送

HID 发送实际按键，不发送任意 Unicode 字符。文本发送使用 US ASCII / 美式键盘布局，支持英文、数字、标点和换行；接收端布局不同可能影响标点。

中文沿用**接收端免安装**的拼音方案：在接收端选择系统现有的拼音输入法，通过 AirDeck 输入拼音并选字。不能把整段汉字、表情或其他 Unicode 文本直接转换为 HID 输入。

## 自动重连

首次需完成配对并手动成功连接一次。应用保存最后成功连接的设备；本次会话若手动选择了其他设备，会优先尝试新目标，不会在失败后自动切回旧目标。

前台、蓝牙开启、权限齐全、HID 服务注册完成且目标仍已配对时，应用才会自动连接。打开应用、返回前台、蓝牙重新开启后完成注册，或连接意外断开，均可触发恢复。

每轮最多 4 次，调度延迟约为 0.6、2、5、10 秒；每次连接另有最多 20 秒的超时。次数用尽后可手动点选设备，或在未暂停自动连接的情况下返回前台/重启蓝牙以开始新一轮。

**手动断开会暂停本次应用会话的自动连接。** 返回前台和重启蓝牙不解除暂停；手动点选设备或完整停止应用后重新启动可恢复。进入后台会取消尚未执行的重试，并请求取消仍在进行的自动连接，不主动断开已经成功建立的连接。应用不提供持续后台重连服务。

## 连接诊断与旧配对缓存

“设备 → 连接诊断”显示连接状态、自动重连状态、提交报告和发送失败次数。提交成功只表示本机蓝牙接受报告，不保证接收端已正确解析。

实际可观察的异常包括基础键鼠模式、未知协议、错误报告类型/编号和不符合预期的键盘反馈。基础键鼠模式无法使用手柄或鼠标滚轮；正常键盘 LED 反馈和常规 Feature 探测不会被直接判作旧缓存。

发送端无法读取或自动清除接收端缓存的 HID 描述符。“未发现可检测的协议异常”不等于缓存正确。

### 从其他蓝牙键鼠应用切换后输入错位

同一操控端切换应用时，蓝牙地址不变，接收端可能继续使用旧应用的 HID 描述符。项目调试曾确认旧描述符为 192 字节、鼠标 ID 1 / 键盘 ID 2 / 媒体 ID 3，而 AirDeck 为 240 字节、键盘 ID 1 / 鼠标 ID 2 / 手柄 ID 3，导致输入被错误解释。

已验证的恢复步骤：

1. 在接收端取消与操控端的配对。
2. 在操控端也取消与接收端的配对。
3. 双方分别关闭蓝牙后重新开启。
4. 关闭其他模拟键鼠应用，打开 AirDeck 并保持前台，等待 HID 注册。
5. 重新配对、连接，再验证键盘、鼠标和手柄。

仅断开再连接不保证清除旧描述符。当前描述符 Java 数组哈希为 `43183451`；这是便于识别的摘要，严格一致性仍以逐字节比较为准。

## 构建与测试

在项目目录执行离线 SDK 构建：

```powershell
pwsh -ExecutionPolicy Bypass -File .\build.ps1
```

需要 Android SDK `platforms/android-35`、`build-tools/35.0.0` 和可用 JDK。脚本默认使用 `%LOCALAPPDATA%\Android\Sdk` 与 `%LOCALAPPDATA%\Programs\Android Studio\jbr`；可通过 `-AndroidSdk`、`-JavaHome` 覆盖。若存在兼容的本地 JDK 8 编译器，脚本会优先使用它处理 Java 编译，以绕过部分受限 Windows 环境中的 JDK ZIP 文件系统问题。

脚本通过 aapt2、javac、D8、zipalign 和 apksigner 构建，不下载 Maven 依赖。输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

调试密钥位于被忽略的 `.build/debug.keystore`；构建产物为调试签名。项目也提供 Gradle 8.12 wrapper 和 Android Gradle Plugin 8.7.3 配置，在依赖就绪时可使用 `gradlew.bat assembleDebug`。

运行纯 Java 测试：

```powershell
pwsh -ExecutionPolicy Bypass -File .\tests\run-tests.ps1
```

测试覆盖 HID 描述符、报告编码、数值边界、重连目标、退避、手动暂停，以及键盘布局、Shift/LED 状态和不同窗口尺寸。测试不依赖手机，也不代替实机输入验证。

## 已完成的验证基线

- 两台 Android 设备间验证了键盘按下/松开、鼠标移动和左键拖动/释放。
- 手柄被识别为 `GAMEPAD | JOYSTICK`；面键、菜单键、肩键、扳机、L3、左右摇杆和方向帽有有效事件且松开后回到中立。R3 未单独覆盖；PPSSPP 中按键与摇杆已确认正常。
- 四种手柄预设、键盘和键鼠横屏控制区完成目视检查，系统返回可回到竖屏。
- 应用启动和进程重启后，自动连接在第 1 次尝试成功；测试环境中重启到连接约 1.7 秒。手动断开后返回前台仍保持暂停，完整停止应用后重启恢复自动连接。
- 既有纯 Java 协议、连接策略和键盘测试已通过，覆盖布局完整性与唯一性、ANSI 行结构、多种窗口尺寸和密度下的边界与重叠、字母和上档符号、修饰键与 Caps LED 状态。
- 正常实机连接未出现协议告警；协议异常分支经过单元测试，未做真实故障注入。

已有构建已成功安装到物理设备。最新 87 键 / 80 键竖屏分区、键帽提示和页面留白仍待最终构建与测试；此前结果不代表最新布局已通过模拟器或物理设备验证，纯 Java 布局和状态测试也不能替代实际输入验证。原始设备日志、界面层级和截图仅用于本地调试，不纳入公开仓库。

## 接收端调试入口

正常使用无需在接收端安装应用。开发测试时，可临时安装调试 APK 并显式打开 `InputProbeActivity`；它没有独立桌面入口，只记录自身前台窗口收到的按键和运动事件，不注册 HID 发送服务。

以下命令假定电脑只连接一台用于诊断的接收端，使用 `-d` 选择该 USB 设备：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -d install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -d shell am start -n com.airdeck.hid/.InputProbeActivity
& $adb -d shell run-as com.airdeck.hid cat files/probe-events.txt
& $adb -d shell run-as com.airdeck.hid cat files/probe-devices.txt
& $adb -d shell dumpsys input
& $adb -d logcat -d -s 'AirDeckProbe:I' '*:S'
```

让测试页保持前台，在操控端依次操作键盘、鼠标和手柄。系统返回退出；重新创建测试 Activity 或点击清空按钮会清除上一轮记录。`run-as` 需要调试构建。日志可能包含设备标识，分享前需自行清理。

运行测试脚本后，可以打印当前源码的描述符，或与自己采集的描述符文件比较：

```powershell
$java = "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\java.exe"
& $java -cp .\tests\.classes CompareDescriptor
& $java -cp .\tests\.classes CompareDescriptor '<descriptor-file>'
```

匹配输出 `MATCH`；不匹配输出 `MISMATCH` 并以退出码 1 结束。修改描述符后应重新配对，并重新采集接收端结果。

## 代码结构

Java 源码位于 `app/src/main/java/com/airdeck/hid/`。

| 文件 | 职责 |
| --- | --- |
| `MainActivity.java` | 主界面、模式切换、设备和权限交互 |
| `KeyboardLayout.java` / `KeyboardView.java` | 完整键盘与精简主区定义、键帽状态、多点按键输入 |
| `CombinedControlsView.java` | 键盘与触控板组合面板 |
| `TouchpadView.java` | 触控板手势 |
| `GamepadPreset.java` / `GamepadView.java` | 四种手柄预设与多点操作 |
| `HidController.java` | HID 注册、连接、报告发送与释放 |
| `ReconnectPolicy.java` | 重连目标、退避和手动暂停 |
| `HidProtocolDiagnostics.java` | 可观察的协议异常判断 |
| `HidReports.java` | 描述符与报告编码 |
| `InputProbeActivity.java` | 接收端前台输入诊断 |

资源位于 `app/src/main/res/`，协议及策略测试位于 `tests/`。交互参考依据见 [reference-analysis.md](reference-analysis.md)。界面与图标自行实现，未复用参考应用的专有素材。
