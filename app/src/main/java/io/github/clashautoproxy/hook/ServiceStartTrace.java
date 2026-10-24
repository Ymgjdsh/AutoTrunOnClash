package io.github.clashautoproxy.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.clashautoproxy.Contract;

/** Read-only, bounded tracing of one explicit CMFA request, including app-side requests. */
final class ServiceStartTrace {
    private static final String KEY = "clashautoproxy.trace";
    private final ThreadLocal<Capture> current = new ThreadLocal<>();
    private volatile Capture active;
    private final ArrayList<String> installed = new ArrayList<>();
    private static final class Capture {
        final String pkg;
        final long since = SystemClock.elapsedRealtime();
        final ArrayDeque<String> lines = new ArrayDeque<>();
        Capture(String pkg) { this.pkg = pkg; }
        synchronized void add(String text) {
            if (lines.size() >= 40) lines.removeFirst();
            lines.addLast("+" + (SystemClock.elapsedRealtime() - since) + "ms "
                    + text.substring(0, Math.min(600, text.length())));
        }
        synchronized ArrayList<String> snapshot() { return new ArrayList<>(lines); }
    }

    void begin(ComponentName name) {
        Capture c = new Capture(name.getPackageName());
        c.add("目标 " + name.flattenToShortString());
        active = c;
    }
    ArrayList<String> snapshot() { Capture c = active; return c == null ? new ArrayList<>() : c.snapshot(); }
    String coverage() { return String.join(", ", installed); }

    void install(ClassLoader loader) {
        try {
            Class<?> services = XposedHelpers.findClass("com.android.server.am.ActiveServices", loader);
            hook(services, "startServiceLocked", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (current.get() != null) return;
                        Capture c = active;
                        if (c == null || SystemClock.elapsedRealtime() - c.since > 6000) return;
                        for (Object a : p.args) {
                            if (!(a instanceof Intent)) continue;
                            ComponentName n = ((Intent) a).getComponent();
                            if (n == null || !c.pkg.equals(n.getPackageName())
                                    || !(Contract.TUN_SERVICE.equals(n.getClassName()) || Contract.CORE_SERVICE.equals(n.getClassName()))) continue;
                            p.setObjectExtra(KEY, c); current.set(c);
                            c.add("startServiceLocked 进入 " + n.getClassName()
                                    + " BinderUID=" + android.os.Binder.getCallingUid());
                            break;
                        }
                    } catch (Throwable ignored) { /* Never affect the original framework call. */ }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Capture c = (Capture) p.getObjectExtra(KEY);
                    if (c == null) return;
                    try { c.add("startServiceLocked 返回 " + result(p)); }
                    catch (Throwable ignored) {}
                    finally { current.remove(); }
                }
            });
            observe(services, "retrieveServiceLocked");
            observe(services, "appRestrictedAnyInBackground");
            observe(services, "isBgFgsRestrictionEnabled");
            observe(services, "deferServiceBringupIfFrozenLocked");
            observe(services, "requestStartTargetPermissionsReviewIfNeededLocked");
            observe(services, "startServiceInnerLocked");
            hook(services, "setFgsRestrictionLocked", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Capture c = current.get(); if (c == null) return;
                        for (Object a : p.args) if (a != null && "com.android.server.am.ServiceRecord".equals(a.getClass().getName())) {
                            c.add("FGS允许启动=" + XposedHelpers.callMethod(a, "isFgsAllowedStart")); break;
                        }
                    } catch (Throwable ignored) {}
                }
            });
            observe(XposedHelpers.findClass("com.android.server.am.ActivityManagerService", loader), "getAppStartModeLOSP");
        } catch (Throwable ignored) { /* Optional diagnostics; control remains usable. */ }
    }

    private void observe(Class<?> type, String method) {
        hook(type, method, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Capture c = current.get(); if (c == null) return;
                    String value;
                    if ("retrieveServiceLocked".equals(method) && !p.hasThrowable() && p.getResult() != null) {
                        Object record = XposedHelpers.getObjectField(p.getResult(), "record");
                        value = record != null ? "已找到服务记录" : "拒绝：" + XposedHelpers.getObjectField(p.getResult(), "permission");
                    } else value = result(p);
                    c.add(method + " = " + value);
                } catch (Throwable ignored) {}
            }
        });
    }
    private void hook(Class<?> type, String name, XC_MethodHook hook) {
        try { if (!XposedBridge.hookAllMethods(type, name, hook).isEmpty()) installed.add(name); }
        catch (Throwable ignored) {}
    }
    private static String result(XC_MethodHook.MethodHookParam p) {
        if (p.hasThrowable()) return p.getThrowable().getClass().getSimpleName() + ": " + p.getThrowable().getMessage();
        Object value = p.getResult();
        return value == null ? "null" : value instanceof ComponentName
                ? ((ComponentName) value).flattenToShortString() : String.valueOf(value);
    }
}
