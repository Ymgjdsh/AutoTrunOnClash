# 架构与接口

## APK 与系统模块

设置界面和 LSPosed 模块包含在同一 APK 中。模块仅在 android 系统框架进程接入；设置应用不申请网络、无障碍、使用情况访问权限，也不运行自己的常驻前台服务或申请 su。

系统回调只读取所需对象，并向独立 HandlerThread 投递事件。Binder IPC、配置读取、服务控制和状态判断均在工作线程执行，避免在系统框架锁中执行这些操作。异常隔离在模块入口，接口缺失时报告覆盖范围。

## 配置与状态

配置使用应用私有 SharedPreferences。SettingsProvider 校验调用 UID，仅接受应用自身或系统 UID；ContentObserver 通知系统侧重新读取配置。

配置、PING、状态分别使用独立 URI。设置页请求连接检查后必须获得新的系统回报，不能把旧缓存当成有效连接。状态和最多 100 条诊断事件仅保留在内存，不持续写入日志文件。

## 前台和后台生命周期

观察 ActivityTaskSupervisor 的 top-resumed 状态。前台事件负责触发启动；后台进程自行启动不触发代理。

AppLifecycleHooks 观察进程启动、死亡、绑定、状态变化和任务移除。相关包 / UID 的事件在工作线程合并，附带一次有限补查，无持续扫描。

AppPresencePolicy 将有最近任务的存活进程或实际活跃后台进程计入运行。纯缓存且无任务的进程、只有旧任务但进程已结束的应用不计入；只统计所选包及主用户。查询失败不能伪装为空列表，以免错误关闭代理。

## 启停状态机

ProxyMachine 通过 Gateway、Scheduler、Listener 与平台交互，可以用虚拟时钟验证。主要状态包括 DISABLED、WAITING、STARTING、OWNED、MANUAL、WAIT_STOP、STOPPING、TESTING、ERROR。

默认后台保留模式下，所有所选应用结束后建立可取消的延迟关闭任务；任一恢复运行则取消。延迟到期前再次核对应用状态。可选旧模式按离开前台安排关闭。熄屏关闭为独立选项，默认不启用。

已有服务视为外部开启，不能接管；已确认服务的 activeSince 改变时交还控制。关闭前再次核对原实例，只针对模块拥有的实例请求停止；启动确认中的极短窗口存在归属识别边界。

## Clash 控制

VPN 模式默认使用导出的 ExternalControlActivity / START_CLASH，让 Clash 在自身进程开启服务。校验实际安装包的组件、导出状态与 Intent 声明。绝不使用 TOGGLE；官方控制页单独创建临时任务并有限保留原前台规则。

关闭官方入口选项时使用 ActivityManagerInternal.startServiceInPackage；只有内部接口不可用才回退到系统 Context。仅核心模式使用直接路径。返回 null 或超时不叠加其他启动请求。

关闭使用包限定的 CLASH_REQUEST_STOP 广播，并指定 Clash 的接收权限。原实例未退出时最多补一次官方 STOP_CLASH，不强停整个客户端。服务及 VPN 残留均确认退出后才报告关闭成功。

启动前确认既有 VPN 授权和始终开启配置，不修改 AppOps 或代替用户授权。默认保护其他 VPN；状态未知时保守失败。

## VPN 查询兼容

VpnStateReader 优先在 system_server 中读取 user 0 的本地只读快照，锁顺序与 AOSP 一致：mVpns → Vpn，读取 mConfig 的 user / interfaze，不修改对象。

本地结构不兼容时依次尝试系统查询接口、系统网络列表。所有路径失败不能判作没有 VPN；网络回退中被隐藏或不能唯一映射的所有者保持 UNKNOWN。启动保护与关闭确认共用读取策略。诊断记录实际路径、原因链，相同连续错误不重复刷满事件历史。

## 界面

使用官方 Material Components 1.13.0 的 Material 3 DayNight 主题与组件。UiApplication 处理深浅色及可选动态配色；Ui 统一控件和系统边距；SettingsActivity 管理配置和状态；AppPickerActivity 通过 RecyclerView 展示应用名单。切换外观不重新发送代理命令。

## 接口依据

- [AOSP Android 15 系统源码](https://github.com/aosp-mirror/platform_frameworks_base/tree/android15-release)
- [VpnManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/VpnManagerService.java)
- [Vpn](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/services/core/java/com/android/server/connectivity/Vpn.java)
- [ContextImpl](https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/app/ContextImpl.java)
- [Clash Meta for Android](https://github.com/MetaCubeX/ClashMetaForAndroid)
- [Material Components 1.13.0](https://github.com/material-components/material-components-android/tree/1.13.0)

厂商内部实现可能不同，需按实际 ROM 验收；上述接口不是稳定 SDK 契约。
