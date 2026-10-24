package io.github.clashautoproxy.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import java.util.ArrayList;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Hook callbacks only read local framework fields and enqueue filtered events. */
final class AppLifecycleHooks {
    private final Supplier<SystemController> controller;
    private final ArrayList<String> coverage = new ArrayList<>();
    AppLifecycleHooks(Supplier<SystemController> controller) { this.controller = controller; }
    String coverage() { return String.join(", ", coverage); }
    void install(ClassLoader loader) {
        XC_MethodHook process = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { if (p.args.length > 0) process(p.args[0]); } catch (Throwable ignored) {}
            }
        };
        hook(loader, "com.android.server.am.ProcessList", "addProcessNameLocked", process);
        hook(loader, "com.android.server.am.ActivityManagerService", "handleAppDiedLocked", process);
        hook(loader, "com.android.server.am.ActivityManagerService", "finishAttachApplicationInner", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    SystemController c = controller.get();
                    if (c != null && p.args.length > 1 && p.args[1] instanceof Integer) c.onApplicationUid((Integer) p.args[1]);
                } catch (Throwable ignored) {}
            }
        });
        hook(loader, "com.android.server.am.ProcessStateRecord", "setReportedProcState", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try { process(XposedHelpers.getObjectField(p.thisObject, "mApp")); } catch (Throwable ignored) {}
            }
        });
        XC_MethodHook task = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    SystemController c = controller.get(); if (c == null || p.args.length == 0 || p.args[0] == null) return;
                    Object t = p.args[0];
                    int user = XposedHelpers.getIntField(t, "mUserId");
                    Intent intent = (Intent) XposedHelpers.callMethod(t, "getBaseIntent");
                    ComponentName component = intent == null ? null : intent.getComponent();
                    if (user == 0 && component != null) c.onApplicationPackage(component.getPackageName(), -1);
                } catch (Throwable ignored) {}
            }
        };
        hook(loader, "com.android.server.wm.RecentTasks", "remove", task);
        hook(loader, "com.android.server.wm.RecentTasks", "notifyTaskRemoved", task);
        hook(loader, "com.android.server.wm.ActivityTaskSupervisor", "removeTask", task);
    }
    private void process(Object record) {
        SystemController c = controller.get(); if (c == null || record == null) return;
        ApplicationInfo info = (ApplicationInfo) XposedHelpers.getObjectField(record, "info");
        if (info != null && info.uid / 100000 == 0) c.onApplicationPackage(info.packageName, info.uid);
    }
    private void hook(ClassLoader loader, String cls, String name, XC_MethodHook hook) {
        try {
            if (!XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, loader), name, hook).isEmpty()) coverage.add(name);
        } catch (Throwable ignored) {}
    }
}
