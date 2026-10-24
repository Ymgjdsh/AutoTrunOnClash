package io.github.clashautoproxy.hook;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import io.github.clashautoproxy.core.AppPresencePolicy;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.clashautoproxy.Contract;
import io.github.clashautoproxy.BuildConfig;
import io.github.clashautoproxy.core.ProxyMachine;
import io.github.clashautoproxy.core.ServiceStartPolicy;
import io.github.clashautoproxy.core.VpnSafetyPolicy;
import io.github.clashautoproxy.core.VpnStateReader;

/** All Binder IPC and control occur on this guarded worker, outside framework locks. */
final class SystemController {
    private final Context context;
    private final Handler handler;
    private final ProxyMachine machine;
    private final ActivityManager activityManager;
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final String session = Long.toHexString(SystemClock.elapsedRealtimeNanos());
    private final Bundle health = new Bundle();
    private final Bundle launch = new Bundle();
    private final FrameworkServiceStarter serviceStarter;
    private final ServiceStartTrace startupTrace;
    private final String appHookCoverage;
    private volatile Set<String> watchedPackages = Set.of();
    private volatile Set<Integer> watchedUids = Set.of();
    private final Object appEventLock = new Object();
    private boolean appEventQueued;
    private long applicationCheckedAt;
    private long controlUntil;
    private String controlOperation = "";
    private String observedVpnOwner = "", observedVpnInterface = "";
    private String vpnReadSource = "", vpnReadDetail = "";
    private long vpnCheckedAt;
    private final Runnable applicationCheck = this::applicationsSignal;
    private final Runnable applicationFollowup = this::applicationsSignal;
    private int configRetryCount;
    private final Runnable configRetry = this::readConfig;
    private boolean loaded, serviceHook, forceReport, officialStart = true, offeredControl;
    private String offered = "", runtimeError = "", lastFingerprint = "";
    private int offeredUser = -1, offeredActivity;
    private long commandId;
    private ProxyMachine.Config config;
    private ProxyMachine.Status status;
    private volatile String proxyPackage = Contract.DEFAULT_PROXY, previousProxy = "";
    private final Runnable serviceChange = () -> machineSignal();

    SystemController(Context context, boolean serviceHook, ClassLoader frameworkLoader, ServiceStartTrace startupTrace, String appHookCoverage) {
        this.context = context;
        this.startupTrace = startupTrace;
        this.appHookCoverage = appHookCoverage;
        serviceStarter = new FrameworkServiceStarter(frameworkLoader);
        this.serviceHook = serviceHook;
        activityManager = context.getSystemService(ActivityManager.class);
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(KeyguardManager.class);
        HandlerThread thread = new HandlerThread("ClashAutoProxy", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper()) {
            @Override public void dispatchMessage(android.os.Message msg) {
                try { super.dispatchMessage(msg); }
                catch (Throwable error) {
                    runtimeError = "内部任务异常已隔离：" + error.getClass().getSimpleName();
                    try { log(runtimeError); publish(true); }
                    catch (Throwable ignored) { XposedBridge.log("ClashAutoProxy: worker failed safely"); }
                }
            }
        };
        Map<Object, Runnable> timers = new HashMap<>();
        machine = new ProxyMachine(new Gateway(), new ProxyMachine.Scheduler() {
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void after(Object key, long delay, Runnable action) {
                cancel(key);
                Runnable task = () -> { timers.remove(key); action.run(); };
                timers.put(key, task); handler.postDelayed(task, delay);
            }
            @Override public void cancel(Object key) {
                Runnable task = timers.remove(key); if (task != null) handler.removeCallbacks(task);
            }
        }, new ProxyMachine.Listener() {
            @Override public void status(ProxyMachine.Status next) {
                status = next; publish(forceReport || Contract.PACKAGE.equals(next.foreground()));
            }
            @Override public void log(String message) { SystemController.this.log(message); }
        });
        handler.post(this::initialize);
    }

    void onForeground(String pkg, int user, boolean controlActivity, int activityIdentity) {
        synchronized (this) {
            if (pkg == null || pkg.equals(offered) && user == offeredUser && controlActivity == offeredControl && activityIdentity == offeredActivity) return;
            offered = pkg; offeredUser = user; offeredControl = controlActivity; offeredActivity = activityIdentity;
        }
        handler.post(() -> {
            if (!loaded) readConfig();
            if (controlActivity && pkg.equals(proxyPackage) && SystemClock.elapsedRealtime() < controlUntil) {
                log("官方 " + controlOperation + " 控制页面短暂进入前台，最多保留原前台规则 1.5 秒");
                machine.controlForeground(pkg, user);
            } else machine.foreground(pkg, user);
        });
    }

    void onServiceChanged(String pkg) {
        if (!pkg.equals(proxyPackage) && !pkg.equals(previousProxy)) return;
        // Service hooks may run under the AMS lock: only enqueue here.
        handler.post(() -> {
            if (machine.relevantPackage(pkg)) {
                handler.removeCallbacks(serviceChange);
                handler.postDelayed(serviceChange, 120);
            }
        });
    }

    void onApplicationUid(int uid) { if (watchedUids.contains(uid)) queueApplicationCheck(); }
    void onApplicationPackage(String pkg, int uid) {
        if (watchedPackages.contains(pkg) || watchedUids.contains(uid)) queueApplicationCheck();
    }
    private void queueApplicationCheck() {
        synchronized (appEventLock) { if (appEventQueued) return; appEventQueued = true; }
        handler.postDelayed(() -> {
            synchronized (appEventLock) { appEventQueued = false; }
            handler.removeCallbacks(applicationCheck); handler.removeCallbacks(applicationFollowup);
            applicationCheck.run();
            // One finite follow-up catches asynchronous process cleanup after task removal.
            handler.postDelayed(applicationFollowup, 1000);
        }, 200);
    }

    private void machineSignal() { if (loaded) machine.serviceChanged(); }
    private void applicationsSignal() { if (loaded) machine.applicationsChanged(); }
    private boolean interactive() { return power.isInteractive() && !keyguard.isKeyguardLocked(); }

    private void initialize() {
        try {
            ContentObserver observer = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    if (Contract.PING_URI.equals(uri)) {
                        forceReport = true;
                        try { readConfig(); machine.serviceChanged(); machine.report(); }
                        finally { forceReport = false; }
                    } else readConfig();
                }
            };
            context.getContentResolver().registerContentObserver(Contract.CONFIG_URI, false, observer);
            context.getContentResolver().registerContentObserver(Contract.PING_URI, false, observer);
            IntentFilter screen = new IntentFilter();
            screen.addAction(Intent.ACTION_SCREEN_OFF); screen.addAction(Intent.ACTION_SCREEN_ON);
            screen.addAction(Intent.ACTION_USER_PRESENT); screen.addAction(Intent.ACTION_USER_UNLOCKED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (Intent.ACTION_USER_UNLOCKED.equals(i.getAction())) readConfig();
                    machine.screen(!Intent.ACTION_SCREEN_OFF.equals(i.getAction()) && interactive());
                }
            }, screen, null, handler, Context.RECEIVER_EXPORTED);
            machine.screen(interactive());
            log("系统事件已接入；服务生命周期监听：" + serviceHook);
            readConfig();
        } catch (Throwable error) {
            runtimeError = "初始化失败：" + error.getClass().getSimpleName() + ": " + error.getMessage();
            log(runtimeError); XposedBridge.log("ClashAutoProxy: " + runtimeError); publish(true);
        }
    }

    private void readConfig() {
        try {
            if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
            Bundle b = context.getContentResolver().call(Contract.CONFIG_URI, "config", null, null);
            if (b == null) return;
            handler.removeCallbacks(configRetry); configRetryCount = 0;
            String next = b.getString("proxyPackage", Contract.DEFAULT_PROXY);
            if (!next.equals(proxyPackage)) { previousProxy = proxyPackage; proxyPackage = next; }
            ArrayList<String> apps = b.getStringArrayList("packages");
            HashSet<String> selected = apps == null ? new HashSet<>() : new HashSet<>(apps);
            selected.remove(Contract.PACKAGE); selected.remove(next);
            watchedPackages = Set.copyOf(selected);
            Set<Integer> uids = new HashSet<>();
            for (String pkg : selected) {
                try { uids.add(context.getPackageManager().getApplicationInfo(pkg, 0).uid); }
                catch (PackageManager.NameNotFoundException ignored) {}
            }
            watchedUids = Set.copyOf(uids);
            ProxyMachine.Config candidate = new ProxyMachine.Config(b.getBoolean("enabled"), selected,
                    new ProxyMachine.Target(next, b.getBoolean("vpn", true) ? Contract.TUN_SERVICE : Contract.CORE_SERVICE),
                    b.getInt("delay", 3), true, b.getBoolean("backgroundStopOnScreenOff", false),
                    b.getBoolean("protectOtherVpn", true), b.getBoolean("ignoreSystemUi", true), b.getBoolean("keepWhileRunning", true));
            boolean first = !loaded;
            boolean nextOfficial = b.getBoolean("officialStart", true);
            boolean routeChanged = officialStart != nextOfficial;
            officialStart = nextOfficial;
            loaded = true; runtimeError = "";
            if (!candidate.equals(config)) { config = candidate; machine.configure(config); }
            if (routeChanged && !first) machine.retry();
            long id = b.getLong("commandId");
            if (!first && id > commandId) {
                switch (b.getString("command", "")) {
                    case "testStart" -> machine.testStart();
                    case "testStop", "release" -> machine.releaseOwned();
                    case "retry" -> machine.retry();
                    case "check" -> checkEnvironment();
                    default -> machine.report();
                }
            }
            commandId = id;
            publish(true);
        } catch (Throwable error) {
            runtimeError = "读取配置失败：" + error.getClass().getSimpleName() + ": " + error.getMessage();
            log(runtimeError); publish(true);
            // A provider may be temporarily unavailable after boot/update. Bounded recovery,
            // not a permanent poll and not a reason to replay stored test commands.
            if (!loaded && configRetryCount < 3) {
                handler.removeCallbacks(configRetry);
                handler.postDelayed(configRetry, 1000L << configRetryCount++);
            }
        }
    }

    private Object vpnManager() {
        Object binder = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.os.ServiceManager", null), "getService", "vpn_management");
        if (binder == null) throw new IllegalStateException("未找到系统 VPN 管理服务");
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.net.IVpnManager$Stub", null), "asInterface", binder);
    }
    private boolean vpnApproved(String pkg) throws PackageManager.NameNotFoundException {
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0));
        return context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow("android:activate_vpn", info.uid, pkg) == AppOpsManager.MODE_ALLOWED;
    }
    private String alwaysOnPackage() {
        Object value = callVpn("getAlwaysOnVpnPackage", 0);
        return value == null ? "" : value.toString();
    }
    private Object callVpn(String method, Object... args) {
        try { return XposedHelpers.callMethod(vpnManager(), method, args); }
        catch (Throwable error) { throw new IllegalStateException("系统 VPN 调用失败：" + method + "；" + VpnStateReader.describe(error), error); }
    }
    private VpnStateReader.State localVpnState() throws Exception {
        try { return VpnStateReader.local(vpnManager(), 0); }
        catch (Throwable error) { throw new IllegalStateException("本地 VPN 快照：" + VpnStateReader.describe(error), error); }
    }
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("MissingPermission")
    private VpnStateReader.State networkVpnState() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) throw new IllegalStateException("网络管理服务不可用");
        Network[] networks = manager.getAllNetworks();
        if (networks == null) throw new IllegalStateException("系统未返回网络列表");
        boolean present = false, unknown = false;
        Set<String> owners = new HashSet<>(), interfaces = new HashSet<>();
        for (Network network : networks) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            // A network may disappear during this read. Do not call a partial snapshot empty.
            if (caps == null) throw new IllegalStateException("网络正在切换，暂不能确认 VPN 状态");
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            present = true;
            int uid = caps.getOwnerUid();
            String[] packages = uid < 0 ? null : context.getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length != 1) unknown = true;
            else owners.add(packages[0]);
            LinkProperties links = manager.getLinkProperties(network);
            if (links != null && links.getInterfaceName() != null) interfaces.add(links.getInterfaceName());
        }
        return new VpnStateReader.State(present, !unknown && owners.size() == 1 ? owners.iterator().next() : "",
                String.join(", ", interfaces), "系统网络列表（兼容回退）", "");
    }
    private VpnStateReader.State vpnState() {
        try {
            VpnStateReader.State state = VpnStateReader.read(this::localVpnState,
                    () -> VpnStateReader.config(callVpn("getVpnConfig", 0), "系统 VPN 查询接口"), this::networkVpnState);
            observedVpnOwner = state.ownerPackage(); observedVpnInterface = state.interfaceName();
            vpnReadSource = state.source(); vpnReadDetail = state.detail();
            return state;
        } catch (Exception error) {
            observedVpnOwner = ""; observedVpnInterface = ""; vpnReadSource = "读取失败";
            vpnReadDetail = VpnStateReader.describe(error);
            throw new IllegalStateException(vpnReadDetail, error);
        } finally { vpnCheckedAt = SystemClock.elapsedRealtime(); }
    }
    private void checkEnvironment() {
        captureEnvironment();
        log("环境检查完成；VPN 已授权：" + health.getBoolean("vpnApproved"));
        publish(true);
    }
    private interface HealthRead { Object read() throws Exception; }
    private void readHealth(String key, HealthRead read) {
        try {
            Object value = read.read();
            if (value instanceof Boolean) health.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) health.putInt(key, (Integer) value);
            else health.putString(key, value == null ? "" : value.toString());
        } catch (Throwable error) {
            String prior = health.getString("checkError", "");
            health.putString("checkError", prior + (prior.isEmpty() ? "" : "; ") + key + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }
    private void captureEnvironment() {
        health.clear();
        readHealth("proxyVersion", () -> context.getPackageManager().getPackageInfo(proxyPackage, 0).versionName);
        readHealth("vpnApproved", () -> vpnApproved(proxyPackage));
        readHealth("alwaysOn", this::alwaysOnPackage);
        readHealth("officialEntry", () -> OfficialClashStarter.resolve(context, proxyPackage).flattenToShortString());
        readHealth("vpnState", () -> {
            VpnStateReader.State vpn = vpnState();
            health.putString("vpnOwnerPackage", vpn.ownerPackage());
            health.putString("vpnInterface", vpn.interfaceName());
            return vpn.present() ? "存在" : "未连接";
        });
        health.putString("vpnReadSource", vpnReadSource); health.putString("vpnReadDetail", vpnReadDetail);
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(proxyPackage, PackageManager.ApplicationInfoFlags.of(0));
            health.putInt("proxyUid", info.uid);
            health.putInt("targetSdk", info.targetSdkVersion);
            health.putBoolean("stopped", (info.flags & ApplicationInfo.FLAG_STOPPED) != 0);
            health.putBoolean("suspended", (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0);
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            readHealth("backgroundOp", () -> ops.unsafeCheckOpNoThrow("android:run_any_in_background", info.uid, proxyPackage));
            readHealth("runBackgroundOp", () -> ops.unsafeCheckOpNoThrow("android:run_in_background", info.uid, proxyPackage));
            readHealth("foregroundOp", () -> ops.unsafeCheckOpNoThrow("android:start_foreground", info.uid, proxyPackage));
        } catch (Throwable error) { health.putString("checkError", health.getString("checkError", "") + "; " + error.getClass().getSimpleName() + ": " + error.getMessage()); }
        health.putLong("checkedAt", SystemClock.elapsedRealtime());
    }

    private final class Gateway implements ProxyMachine.Gateway {
        @SuppressWarnings("deprecation")
        @Override public Set<String> runningSelected(Set<String> selected) {
            List<ActivityManager.RunningAppProcessInfo> apps = activityManager.getRunningAppProcesses();
            List<ActivityManager.RecentTaskInfo> tasks = activityManager.getRecentTasks(256, ActivityManager.RECENT_IGNORE_UNAVAILABLE);
            if (apps == null || tasks == null) throw new IllegalStateException("系统未返回完整的后台进程 / 最近任务信息，未自动关闭代理");
            Set<String> taskPackages = new HashSet<>();
            for (ActivityManager.RecentTaskInfo task : tasks) {
                if (XposedHelpers.getIntField(task, "userId") != 0) continue;
                ComponentName base = task.baseActivity != null ? task.baseActivity : task.baseIntent == null ? null : task.baseIntent.getComponent();
                if (base != null) taskPackages.add(base.getPackageName());
                if (task.origActivity != null) taskPackages.add(task.origActivity.getPackageName());
            }
            ArrayList<AppPresencePolicy.AppProcess> processes = new ArrayList<>();
            for (ActivityManager.RunningAppProcessInfo app : apps) {
                Set<String> packages = app.pkgList == null ? Set.of() : new HashSet<>(Arrays.asList(app.pkgList));
                processes.add(new AppPresencePolicy.AppProcess(app.uid / 100000, app.pid,
                        app.importance > 0 && app.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE,
                        packages, app.processName));
            }
            Set<String> result = AppPresencePolicy.running(selected, taskPackages, processes);
            applicationCheckedAt = SystemClock.elapsedRealtime();
            return result;
        }
        @SuppressWarnings("deprecation")
        @Override public ProxyMachine.Service running(String pkg) {
            for (ActivityManager.RunningServiceInfo info : activityManager.getRunningServices(Integer.MAX_VALUE)) {
                if ((info.started || info.foreground) && android.os.UserHandle.getUserHandleForUid(info.uid).equals(android.os.Process.myUserHandle()) && pkg.equals(info.service.getPackageName()) &&
                        (Contract.TUN_SERVICE.equals(info.service.getClassName()) || Contract.CORE_SERVICE.equals(info.service.getClassName()))) {
                    return new ProxyMachine.Service(new ProxyMachine.Target(pkg, info.service.getClassName()), info.activeSince,
                            info.pid > 0 && info.foreground && info.restarting == 0);
                }
            }
            return null;
        }
        // This class is instantiated only inside system_server (UID 1000), which owns ACCESS_NETWORK_STATE.
        @android.annotation.SuppressLint("MissingPermission")
        @Override public void start(ProxyMachine.Target target, boolean protectOtherVpn) throws Exception {
            ComponentName component = new ComponentName(target.packageName(), target.serviceName());
            boolean useOfficial = officialStart && Contract.TUN_SERVICE.equals(target.serviceName());
            launch.clear();
            launch.putString("component", component.flattenToShortString());
            launch.putString("route", useOfficial ? "Clash 官方 START_CLASH 入口" : "应用身份 / ActivityManagerInternal");
            launch.putLong("requestedAt", SystemClock.elapsedRealtime());
            captureEnvironment();
            launch.putBundle("before", new Bundle(health));
            startupTrace.begin(component);
            try {
            ServiceInfo info = context.getPackageManager().getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0));
            launch.putInt("uid", info.applicationInfo.uid);
            if (!info.enabled || !info.applicationInfo.enabled) throw new IllegalStateException("代理应用或服务已停用");
            if (Contract.TUN_SERVICE.equals(target.serviceName())) {
                if (!vpnApproved(target.packageName())) throw new IllegalStateException("尚未授权 VPN，请在 Clash Meta 中手动连接一次");
                if (!alwaysOnPackage().isEmpty()) throw new IllegalStateException("请先关闭系统的始终开启 VPN，再使用按应用启停");
                if (protectOtherVpn) {
                    VpnSafetyPolicy.requireSafeStart(target.packageName(), vpnState());
                }
                // Only prepare a package whose existing user consent was verified above.
                // Never call setVpnPackageAuthorization or change AppOps / consent settings.
                Object prepared = callVpn("prepareVpn", null, target.packageName(), 0);
                if (!(prepared instanceof Boolean) || !((Boolean) prepared)) throw new IllegalStateException("系统未接受已授权 VPN 的准备请求");
            }
            Intent intent = new Intent().setComponent(component);
            if (useOfficial) {
                controlOperation = "start"; controlUntil = SystemClock.elapsedRealtime() + 6000;
                OfficialClashStarter.start(context, target.packageName());
                launch.putString("outcome", "ACTIVITY_REQUESTED");
                launch.putString("detail", "已请求官方 START_CLASH，由 Clash 在自身进程开启服务；等待实际运行验证。Clash 的 VPN 模式需与模块一致。");
                log("启动路径：Clash 官方 START_CLASH 入口；等待服务验证");
            } else {
                ServiceStartPolicy.Outcome outcome = ServiceStartPolicy.launch(target.packageName(),
                        () -> startResult(serviceStarter.start(info.applicationInfo.uid, target.packageName(), intent)),
                        () -> startResult(context.startForegroundService(intent)),
                        message -> {
                            if (message.startsWith("启动路径：")) launch.putString("route", "应用身份 / ActivityManagerInternal");
                            else if (message.startsWith("应用身份接口不可用：")) launch.putString("route", "系统 Context（接口不兼容回退）");
                            launch.putString("detail", message); log(message);
                        });
                launch.putString("outcome", outcome.name());
            }
            } catch (Exception error) {
                launch.putString("outcome", "FAILED");
                launch.putString("detail", error.getClass().getSimpleName() + ": " + error.getMessage());
                throw error;
            }
        }
        @Override public void startTimedOut(ProxyMachine.Target target) {
            launch.putString("outcome", "TIMEOUT");
            launch.putString("detail", launch.getString("detail", "") + "；5 秒验证超时，未视为成功");
            // Separate fields preserve the pre-request evidence and show current post-failure state.
            captureEnvironment();
            ArrayList<String> trace = startupTrace.snapshot();
            log(trace.size() <= 1 ? "未观察到本次代理的系统服务启动调用；查看官方入口和环境诊断"
                    : "已捕获 " + trace.size() + " 条本次系统服务启动路径，详见诊断");
        }
        @Override public boolean connected(ProxyMachine.Target target) {
            if (!Contract.TUN_SERVICE.equals(target.serviceName())) return false;
            return VpnSafetyPolicy.connected(target.packageName(), vpnState());
        }
        @Override public void stop(ProxyMachine.Target target) {
            // CMFA's normal close path first tears down the TUN/runtime, then calls stopSelf.
            // Restrict delivery to its package and signature-protected receiver permission.
            context.sendBroadcast(new Intent(target.packageName() + ".intent.action.CLASH_REQUEST_STOP")
                    .setPackage(target.packageName()), target.packageName() + ".permission.RECEIVE_BROADCASTS");
            log("关闭路径：Clash 正常停止广播；等待服务和 VPN 隧道退出");
        }
        @Override public boolean stopOwned(ProxyMachine.Service expected) throws Exception {
            ProxyMachine.Service current = running(expected.target().packageName());
            if (current != null && (!current.target().equals(expected.target())
                    || expected.instance() != 0 && current.instance() != expected.instance())) return false;
            stop(expected.target()); return true;
        }
        @Override public void retryStop(ProxyMachine.Service expected) throws Exception {
            ProxyMachine.Service current = running(expected.target().packageName());
            if (current == null || !current.target().equals(expected.target()) || expected.instance() == 0
                    || current.instance() != expected.instance()) return;
            controlOperation = "stop"; controlUntil = SystemClock.elapsedRealtime() + 4000;
            OfficialClashStarter.stop(context, expected.target().packageName());
            log("原实例仍在运行，追加一次官方 STOP_CLASH 请求；不会反复重试");
        }
    }

    private static ServiceStartPolicy.Result startResult(ComponentName component) {
        return component == null ? null : new ServiceStartPolicy.Result(component.getPackageName(), component.getClassName());
    }

    private void log(String message) {
        if (logs.size() >= 100) logs.removeFirst();
        logs.addLast(new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + message);
    }
    private void publish(boolean force) {
        if (!loaded || status == null) return;
        String failure = !runtimeError.isEmpty() ? runtimeError : status.error();
        String fingerprint = status.code() + failure + status.owned() + status.running() + status.pendingUntil();
        if (!force && fingerprint.equals(lastFingerprint)) return;
        try {
            Bundle b = new Bundle();
            b.putBoolean("connected", true); b.putString("session", session);
            b.putString("stateCode", !runtimeError.isEmpty() ? "ERROR" : status.code());
            b.putString("error", failure); b.putString("foreground", status.foreground());
            b.putInt("userId", status.userId()); b.putBoolean("owned", status.owned());
            b.putBoolean("running", status.running()); b.putBoolean("testing", status.testing());
            b.putBoolean("serviceHook", serviceHook); b.putLong("pendingUntil", status.pendingUntil());
            b.putLong("reportedAt", SystemClock.elapsedRealtime()); b.putString("moduleVersion", BuildConfig.VERSION_NAME);
            b.putStringArrayList("logs", new ArrayList<>(logs)); b.putBundle("health", new Bundle(health));
            b.putBundle("launch", new Bundle(launch));
            b.putStringArrayList("startTrace", startupTrace.snapshot());
            b.putString("traceCoverage", startupTrace.coverage());
            b.putString("applicationHooks", appHookCoverage);
            b.putBoolean("presenceKnown", machine.presenceKnown());
            b.putStringArrayList("runningSelected", new ArrayList<>(machine.runningApps()));
            b.putLong("applicationCheckedAt", applicationCheckedAt);
            b.putString("currentVpnOwner", observedVpnOwner); b.putString("currentVpnInterface", observedVpnInterface);
            b.putString("vpnReadSource", vpnReadSource); b.putString("vpnReadDetail", vpnReadDetail);
            b.putLong("vpnCheckedAt", vpnCheckedAt);
            context.getContentResolver().call(Contract.CONFIG_URI, "report", null, b);
            lastFingerprint = fingerprint;
        } catch (Throwable error) { XposedBridge.log("ClashAutoProxy: status unavailable: " + error.getClass().getSimpleName()); }
    }
}
