package io.github.clashautoproxy.core;

import java.util.Objects;
import java.util.Set;

/** Single-threaded, platform-independent state machine. Timers never wake a sleeping device. */
public final class ProxyMachine {
    public record Target(String packageName, String serviceName) {}
    public record Service(Target target, long instance, boolean confirmed) {
        public Service(Target target, long instance) { this(target, instance, true); }
    }
    public record Config(boolean enabled, Set<String> packages, Target target, int delaySeconds,
                         boolean protectManual, boolean stopOnScreenOff,
                         boolean protectOtherVpn, boolean ignoreSystemUi, boolean keepWhileRunning) {
        public Config(boolean enabled, Set<String> packages, Target target, int delaySeconds,
                      boolean protectManual, boolean stopOnScreenOff, boolean protectOtherVpn, boolean ignoreSystemUi) {
            this(enabled, packages, target, delaySeconds, protectManual, stopOnScreenOff, protectOtherVpn, ignoreSystemUi, false);
        }
        public Config {
            packages = Set.copyOf(packages);
            Objects.requireNonNull(target);
            delaySeconds = Math.max(0, Math.min(15, delaySeconds));
        }
    }
    public record Status(String code, String error, String foreground, int userId,
                         boolean owned, boolean running, long pendingUntil, boolean testing) {}
    public interface Gateway {
        Service running(String packageName) throws Exception;
        void start(Target target, boolean protectOtherVpn) throws Exception;
        void stop(Target target) throws Exception;
        default void startTimedOut(Target target) {}
        default boolean connected(Target target) throws Exception { return false; }
        default boolean stopOwned(Service expected) throws Exception { stop(expected.target()); return true; }
        default void retryStop(Service expected) throws Exception {}
        /** Must throw on unknown state, rather than pretending every selected app has exited. */
        default Set<String> runningSelected(Set<String> selected) throws Exception {
            throw new IllegalStateException("后台应用监听尚不可用");
        }
    }
    public interface Scheduler {
        long now();
        void after(Object key, long delayMillis, Runnable task);
        void cancel(Object key);
    }
    public interface Listener {
        void status(Status status);
        void log(String message);
    }
    private static final Set<String> TRANSIENT_UI = Set.of("android", "com.android.systemui",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller");
    private final Gateway gateway;
    private final Scheduler scheduler;
    private final Listener listener;
    private final Object leaveTimer = new Object(), startTimer = new Object(), closeTimer = new Object(), testTimer = new Object(), bridgeTimer = new Object();
    private Config config;
    private String observed = "", effective = "", error = "", code = "DISABLED";
    private int user;
    private boolean awake = true, blocked, starting, stopFailed, unexpectedMode;
    private Target owned;
    private long ownedInstance, generation, exitSince = -1, testUntil;
    private Service live, previousLive, closing;
    private long stopAt;
    private Set<String> runningApps = Set.of();
    private boolean presenceKnown, presenceFailure;

    public ProxyMachine(Gateway gateway, Scheduler scheduler, Listener listener) {
        this.gateway = gateway; this.scheduler = scheduler; this.listener = listener;
    }

    public void configure(Config next) {
        boolean changed = !next.equals(config);
        boolean release = owned != null && (!next.enabled() || config == null || !next.target().equals(config.target()));
        config = next;
        if (changed) { error = ""; blocked = false; stopFailed = false; unexpectedMode = false; }
        if (release) { cancelTest(); cancelLeave(); requestStop(true); }
        reconcile();
    }

    public void foreground(String pkg, int userId) {
        scheduler.cancel(bridgeTimer);
        observed = pkg == null ? "" : pkg;
        user = userId;
        if (config == null || userId != 0 || !config.ignoreSystemUi() || !TRANSIENT_UI.contains(observed)) effective = observed;
        if (!wanted()) blocked = false;
        reconcile();
    }

    /** Only the controller may mark its own exact, short-lived CMFA control Activity. */
    public void controlForeground(String pkg, int userId) {
        if (config == null || userId != 0 || !config.target().packageName().equals(pkg)
                || owned == null) { foreground(pkg, userId); return; }
        observed = pkg; user = userId;
        // Preserve the caller's focus for a finite transition, even with exit delay=0.
        // Ordinary Clash screens never use this path. A stuck control screen expires.
        scheduler.after(bridgeTimer, 1500, () -> foreground(pkg, userId));
        emit();
    }

    public void screen(boolean unlockedAndInteractive) {
        awake = unlockedAndInteractive;
        if (config != null && !awake && config.stopOnScreenOff()) {
            cancelTest(); cancelLeave(); requestStop(false);
        }
        reconcile();
    }

    public void serviceChanged() {
        if (config != null && !config.enabled() && owned == null && closing == null && !testLease()) {
            try { refresh(); } catch (Exception e) { failure("读取服务状态失败", e); return; }
        }
        reconcile();
    }
    public void applicationsChanged() { if (config != null) reconcile(); }
    public Set<String> runningApps() { return runningApps; }
    public boolean presenceKnown() { return presenceKnown; }
    public void retry() { blocked = false; stopFailed = false; unexpectedMode = false; error = ""; reconcile(); }
    public boolean relevantPackage(String pkg) {
        return config != null && (config.target().packageName().equals(pkg)
                || owned != null && owned.packageName().equals(pkg)
                || closing != null && closing.target().packageName().equals(pkg));
    }
    public void testStart() {
        if (config == null) return;
        blocked = false; error = ""; stopFailed = false; unexpectedMode = false; cancelLeave();
        try {
            refresh();
            if (live != null && owned == null) { code = "MANUAL"; emit(); return; }
            if (closing != null) { fail("服务仍在关闭，请稍后再测试"); return; }
            testUntil = scheduler.now() + 10000;
            scheduler.after(testTimer, 10000, () -> { testUntil = 0; reconcile(); });
            requestStart();
            if (owned != null) code = starting ? "STARTING" : "TESTING";
            emit();
        } catch (Exception e) { failure("测试启动失败", e); }
    }
    public void releaseOwned() {
        cancelTest(); cancelLeave(); stopFailed = false;
        blocked = wanted();
        requestStop(true);
        if (closing == null && !stopFailed) { code = live != null ? "MANUAL" : config != null && config.enabled() ? "WAITING" : "DISABLED"; emit(); }
    }
    public void report() { emit(); }

    private boolean wanted() {
        return config != null && config.enabled() && user == 0 && config.packages().contains(effective)
                && (awake || !config.stopOnScreenOff());
    }
    private boolean testLease() { return testUntil > scheduler.now(); }
    private boolean keepOwnedForApplications() throws Exception {
        if (!config.keepWhileRunning()) return wanted();
        try {
            runningApps = Set.copyOf(gateway.runningSelected(config.packages())); presenceKnown = true;
            if (presenceFailure) { presenceFailure = false; error = ""; }
            if (config.packages().contains(effective) && !runningApps.contains(effective)) effective = "";
        } catch (Exception e) { presenceKnown = false; presenceFailure = true; throw e; }
        // Presence only retains an owned service; background events never launch a proxy.
        return knownApplicationsRetain();
    }
    private boolean knownApplicationsRetain() {
        return config != null && config.keepWhileRunning() && presenceKnown && user == 0
                && config.enabled() && (awake || !config.stopOnScreenOff()) && !runningApps.isEmpty();
    }

    private void refresh() throws Exception {
        previousLive = live;
        live = gateway.running(config.target().packageName());
        if (live == null && owned == null && gateway.connected(config.target())) live = new Service(config.target(), 0, true);
        if (owned != null) {
            Service own = owned.packageName().equals(config.target().packageName()) ? live : gateway.running(owned.packageName());
            if (own == null && gateway.connected(owned)) {
                own = new Service(owned, ownedInstance, true);
                if (owned.packageName().equals(config.target().packageName())) live = own;
            }
            if (own != null && (!own.target().equals(owned) || ownedInstance != 0 && own.instance() != ownedInstance)) {
                if (starting && !own.target().equals(owned)) modeMismatch();
                listener.log("服务实例已更换，交还手动控制");
                owned = null; ownedInstance = 0; starting = false;
                scheduler.cancel(startTimer);
            } else if (own == null && !starting && closing == null) {
                owned = null; ownedInstance = 0;
                if (wanted()) {
                    blocked = true;
                    error = "代理服务已停止。为尊重手动停止，本次前台会话不自动重启；离开后重新进入或点重试。";
                }
            } else if (own != null && ownedInstance == 0) ownedInstance = own.instance();
        } else if (config.protectManual() && previousLive != null && live == null && wanted() && closing == null) {
            blocked = true;
            error = "手动代理已停止，本次前台会话不接管重启。";
        }
    }

    private void reconcile() {
        if (config == null) return;
        try {
            if (!config.enabled() && owned == null && closing == null && !testLease()) {
                cancelLeave(); code = error.isEmpty() ? "DISABLED" : "ERROR"; emit(); return;
            }
            refresh();
            if (closing != null) { code = "STOPPING"; emit(); return; }
            boolean retain = config.keepWhileRunning() && keepOwnedForApplications() && owned != null;
            if (wanted() && !blocked) {
                cancelLeave();
                if (live != null) {
                    code = unexpectedMode ? "ERROR" : owned != null && starting && !live.confirmed() ? "STARTING" : owned != null ? "OWNED" : "MANUAL";
                    if (!unexpectedMode) error = "";
                    stopFailed = false;
                }
                else if (starting) code = "STARTING";
                else requestStart();
            } else if (retain && !stopFailed) {
                cancelLeave(); code = starting ? "STARTING" : "OWNED";
            } else if (testLease() && owned != null) {
                cancelLeave(); code = starting ? "STARTING" : "TESTING";
            } else {
                if (!wanted()) blocked = false;
                boolean shouldStop = owned != null || !config.keepWhileRunning() && config.enabled() && !config.protectManual() && live != null;
                if (shouldStop && !stopFailed) {
                    if (!config.enabled() || config.delaySeconds() == 0 || !awake && config.stopOnScreenOff()) {
                        cancelLeave(); requestStop(false);
                    } else {
                        if (exitSince < 0) exitSince = scheduler.now();
                        stopAt = exitSince + config.delaySeconds() * 1000L;
                        code = "WAIT_STOP";
                        scheduler.after(leaveTimer, Math.max(0, stopAt - scheduler.now()), () -> {
                            if (!wanted() && !testLease()) {
                                try {
                                    if (owned != null && keepOwnedForApplications()) { cancelLeave(); reconcile(); }
                                    else { cancelLeave(); requestStop(false); }
                                } catch (Exception e) { failure("无法确认后台应用状态，未自动关闭代理", e); }
                            }
                        });
                    }
                } else {
                    cancelLeave();
                    code = !error.isEmpty() ? "ERROR" : live != null ? "MANUAL" : config.enabled() ? "WAITING" : "DISABLED";
                }
            }
            emit();
        } catch (Exception e) { failure("读取或控制代理服务失败", e); }
    }

    private void requestStart() {
        if (owned != null || starting || closing != null || config == null) return;
        try {
            Service current = gateway.running(config.target().packageName());
            if (current != null) { live = current; code = "MANUAL"; return; }
            gateway.start(config.target(), config.protectOtherVpn());
            owned = config.target(); ownedInstance = 0; starting = true;
            long token = ++generation;
            code = "STARTING"; error = "";
            listener.log("请求启动 " + owned.packageName());
            scheduler.after(startTimer, 1000, () -> verifyStart(token, 0));
        } catch (Exception e) { blocked = true; cancelTest(); failure("启动失败", e); }
    }

    private void verifyStart(long token, int attempt) {
        if (token != generation || owned == null || closing != null) return;
        try {
            Service now = gateway.running(owned.packageName());
            boolean same = now != null && now.target().equals(owned) && (ownedInstance == 0 || ownedInstance == now.instance());
            if (same && now.confirmed()) {
                if (starting) listener.log("已确认服务运行 " + now.target().serviceName() + "，实例=" + now.instance());
                live = now; ownedInstance = now.instance(); starting = false;
                code = presenceFailure ? "ERROR" : wanted() || knownApplicationsRetain() ? "OWNED" : testLease() ? "TESTING" : "WAIT_STOP";
                if (attempt < 2) scheduler.after(startTimer, 2000, () -> verifyStart(token, attempt + 1));
            } else if (now != null && !same) {
                if (!now.target().equals(owned)) modeMismatch();
                owned = null; ownedInstance = 0; starting = false; live = now;
                code = unexpectedMode ? "ERROR" : "MANUAL"; listener.log("启动期间服务实例变化，未接管关闭");
            } else if (attempt < 2) {
                scheduler.after(startTimer, 2000, () -> verifyStart(token, attempt + 1));
            } else {
                Target failed = owned;
                owned = null; ownedInstance = 0; starting = false; blocked = true; cancelTest();
                gateway.startTimedOut(failed);
                fail("启动请求后 5 秒内未确认代理服务持续运行。已记录本次启动环境和系统路径，请复制诊断信息。可在代理服务设置中切换启动方式；不会自动重复发送。");
                return;
            }
            emit();
        } catch (Exception e) { starting = false; blocked = true; failure("检查启动结果失败", e); }
    }

    private void requestStop(boolean onlyOwned) {
        if (config == null || closing != null || stopFailed) return;
        try {
            refresh();
            Service victim;
            if (owned != null) victim = new Service(owned, ownedInstance);
            else if (!onlyOwned && !config.keepWhileRunning() && config.enabled() && !config.protectManual()) victim = live;
            else { code = live != null ? "MANUAL" : config.enabled() ? "WAITING" : "DISABLED"; emit(); return; }
            if (victim == null) return;
            starting = false; scheduler.cancel(startTimer);
            if (!gateway.stopOwned(victim)) {
                owned = null; ownedInstance = 0; live = gateway.running(config.target().packageName());
                blocked = wanted(); code = live == null ? "WAITING" : "MANUAL";
                listener.log("关闭前检测到服务实例已改变，取消关闭并交还手动控制"); emit(); return;
            }
            closing = victim; code = "STOPPING"; error = "";
            long token = ++generation;
            listener.log("请求关闭 " + victim.target().packageName());
            scheduler.after(closeTimer, 300, () -> verifyStop(token, 0));
            emit();
        } catch (Exception e) { stopFailed = true; failure("关闭失败；保留归属供手动重试", e); }
    }

    private void verifyStop(long token, int attempt) {
        if (token != generation || closing == null) return;
        try {
            Service current = gateway.running(closing.target().packageName());
            boolean same = current != null && current.target().equals(closing.target())
                    && (closing.instance() == 0 || current.instance() == closing.instance());
            if (current == null && gateway.connected(closing.target())) {
                same = true; live = new Service(closing.target(), closing.instance(), true);
            }
            if (!same) {
                boolean replaced = current != null;
                if (owned != null && owned.equals(closing.target())) { owned = null; ownedInstance = 0; }
                closing = null; live = current;
                listener.log(replaced ? "服务已更换，未继续关闭新实例" : "已确认服务关闭，连接残留检查通过");
                if (replaced && config.protectManual()) { code = "MANUAL"; emit(); }
                else reconcile();
            } else if (attempt < 3) {
                if (attempt == 2) gateway.retryStop(closing);
                scheduler.after(closeTimer, 500L << attempt, () -> verifyStop(token, attempt + 1));
            } else {
                closing = null; stopFailed = true;
                fail("关闭请求已发送，但原服务或同包 VPN 隧道仍未退出。未误报关闭，也未终止整个 Clash 应用；可点释放重试。");
            }
        } catch (Exception e) { closing = null; stopFailed = true; failure("检查关闭结果失败", e); }
    }

    private void cancelLeave() { scheduler.cancel(leaveTimer); exitSince = -1; stopAt = 0; }
    private void modeMismatch() {
        unexpectedMode = true; blocked = true;
        error = "Clash 实际开启的模式与模块不一致。请在 Clash 内开启 VPN 模式，或关闭模块的官方开启入口后重试。未接管不匹配的服务，请先在 Clash 中手动关闭。";
        listener.log(error);
    }
    private void cancelTest() { scheduler.cancel(testTimer); testUntil = 0; }
    private void failure(String message, Exception e) { fail(message + "\n" + e.getClass().getSimpleName() + ": " + e.getMessage()); }
    private void fail(String message) {
        boolean repeated = message.equals(error) && "ERROR".equals(code);
        error = message; code = "ERROR";
        if (!repeated) listener.log(message);
        emit();
    }
    private void emit() {
        listener.status(new Status(code, error, observed, user, owned != null, live != null && live.confirmed(),
                "WAIT_STOP".equals(code) ? stopAt : 0, testLease()));
    }
}
