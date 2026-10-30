# Clash 自动代理

面向 Android 15（API 35）起的 APK + LSPosed 模块。打开指定应用时自动请求启动 Clash Meta；默认只要任一所选应用仍在运行就保持代理，全部结束后延迟关闭模块自己启动的代理。

当前源码版本：**0.2.5**。设置界面采用官方 Material Components 的 Material 3 组件与主题。

## 功能

- 图形化选择触发应用，支持名称 / 包名搜索、已选 / 已失效筛选及未保存修改保护。
- 基于系统前台、任务和进程生命周期事件判断状态，不持续轮询、不持有唤醒锁。
- 默认保留仍在运行的所选后台应用；全部结束后按 0–15 秒设置延迟关闭，重新运行时取消关闭。
- 始终保护已有手动代理；检测到服务实例被其他渠道替换时交还手动控制。
- 支持其他 VPN 保护、可选熄屏关闭、临时系统界面过滤、VPN / 仅核心模式。
- VPN 模式优先使用官方 START_CLASH；兼容客户端未声明此入口时，自动使用经过系统与 VPN 安全检查的直接服务启动路径。
- 启动错误不会再次发送另一启动请求；CMFA 官方 STOP_CLASH 缺失时继续验证原停止请求，不强制停止客户端。
- 提供环境检查、测试、重试、释放和内存诊断；VPN 查询支持兼容回退及底层原因诊断。
- Material 3 浅色 / 深色 / 跟随系统、可选动态配色及系统栏边距适配。

## 使用条件

1. Android 15 或更高版本，以及可用的 Root / LSPosed 兼容实现。
2. 安装 Clash Meta for Android 并配置可用的代理配置。
3. 在 Clash 中开启 VPN 模式、手动授权并连接成功一次，再手动关闭。
4. 模块当前仅支持主用户 user 0；不支持应用分身、工作资料和其他用户。

系统框架接口属于内部接口，厂商 ROM 与 LSPosed 实现需要单独验证。本仓库不承诺所有 Android 15 设备均兼容。

## 安装与设置

1. 按下方说明构建并安装 APK。
2. 在 LSPosed 中启用「Clash 自动代理」，作用域仅选择「系统框架 / android」，然后重启设备。更新模块 APK 后也需要重启以加载系统侧代码。
3. 打开设置，确认系统监听已连接，选择触发应用并开启自动控制。
4. 默认使用 VPN 模式和官方开启入口优先；客户端缺少该入口时自动回退到直接服务启动。Clash 内的运行模式应与模块一致。
5. 打开所选应用应自动开启；返回桌面但所选应用仍在运行时保持；全部结束后按设置延迟关闭。

「锁屏或熄屏时也关闭」默认关闭，开启后会额外释放模块拥有的代理；已有手动代理仍受保护。「测试开启」在没有所选应用运行时约 10 秒后释放，正常后台保留规则优先。

停用前先在设置中关闭自动控制，让模块释放自有代理，再停用 LSPosed 模块并重启。

## 构建

使用 Android SDK platform 35、JDK 21 和 Gradle Wrapper。依赖版本见 Gradle 配置；构建需要访问 Google Maven / Maven Central / Gradle 分发服务。

### Windows PowerShell

配置 JAVA_HOME，以及 ANDROID_HOME 或 ANDROID_SDK_ROOT 后运行：

    .\build.ps1

也可使用脚本的 -AndroidSdk、-JavaHome、-BuildRoot 参数指定自己的路径。脚本会运行 assembleDebug、testDebugUnitTest、lintDebug，并把 APK 和 SHA-256 放到 dist/。

脚本会在本地生成 local.properties，并默认将构建输出放到当前用户 Gradle 缓存的独立目录，规避部分 Windows 工具对非 ASCII 项目路径的限制。这些文件不会提交。

### 其他环境

安装 SDK / JDK，设置 ANDROID_HOME 与 JAVA_HOME 后运行：

    ./gradlew assembleDebug testDebugUnitTest lintDebug

常规构建的 APK 位于 app/build/outputs/apk/debug/app-debug.apk。当前构建使用本地开发签名；仓库不包含开发者的签名密钥，因此自行构建的 APK 不保证可以覆盖安装其他签名版本。

## 源码结构

| 路径 | 内容 |
| --- | --- |
| app/src/main/java/io/github/clashautoproxy/ | 设置界面、应用选择、配置提供器 |
| app/src/main/java/io/github/clashautoproxy/core/ | 可独立测试的启停状态机、后台判定、VPN 读取和保护策略 |
| app/src/main/java/io/github/clashautoproxy/hook/ | 系统事件、代理控制、官方入口及只读诊断 |
| app/src/main/res/ | Material 3 主题、组件和图标 |
| app/src/test/ | 状态机与策略回归测试 |
| app/libs/ | 仅用于编译的 Xposed API 82 及来源说明 |
| gradle/wrapper/ | 构建包装器 |
| docs/ | 通用架构、验收及公开内容说明 |

## 验证与边界

0.2.5 源码具有 121 项单元测试，覆盖官方入口回退、自动启动、最后应用结束关闭、手动保护、实例替换、查询失败、未知 VPN 所有者和有限重试等场景。单元测试、Lint 和成功构建不能代替实际设备验收或功耗测量。

- 启动代理存在时间窗口，不保证目标应用的首个网络请求已走代理。
- 服务运行状态不代表节点、DNS、隧道或网络连通性已验证。
- 始终开启 VPN / 无 VPN 时阻止连接可能与按应用启停冲突，应先关闭这些系统配置再验收。
- 同一服务在启动确认窗口中被外部同时操作时，实例归属识别仍有边界。
- 应用名单和设备诊断在运行时生成；分享诊断前应自行去除个人信息。

详见 [架构说明](docs/ARCHITECTURE.md)、[设备验收清单](docs/DEVICE-TEST.md)、[版本说明](CHANGELOG.md)及[公开内容说明](docs/PRIVACY.md)。
