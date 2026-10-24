package io.github.clashautoproxy.hook;

import android.content.Context;
import android.content.ComponentName;
import android.os.Build;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.clashautoproxy.Contract;

/** Hook callbacks never perform Binder IPC, disk I/O, shell work or service control. */
public final class ModuleEntry implements IXposedHookLoadPackage {
    private volatile SystemController controller;
    private volatile boolean failureLogged;
    private boolean serviceHooks;
    private final ServiceStartTrace startupTrace = new ServiceStartTrace();
    private final AppLifecycleHooks applicationHooks = new AppLifecycleHooks(() -> controller);
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!"android".equals(load.packageName) || !"android".equals(load.processName)) return;
        startupTrace.install(load.classLoader);
        applicationHooks.install(load.classLoader);
        installServiceHooks(load.classLoader);
        try {
            Class<?> supervisor = XposedHelpers.findClass("com.android.server.wm.ActivityTaskSupervisor", load.classLoader);
            if (XposedBridge.hookAllMethods(supervisor, "updateTopResumedActivityIfNeeded", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object activity = XposedHelpers.getObjectField(param.thisObject, "mTopResumedActivity");
                        // Null is common while switching tasks. Screen events handle actual sleep.
                        if (activity == null) return;
                        String pkg = (String) XposedHelpers.getObjectField(activity, "packageName");
                        int user = XposedHelpers.getIntField(activity, "mUserId");
                        SystemController current = controller;
                        if (current == null) {
                            synchronized (ModuleEntry.this) {
                                current = controller;
                                if (current == null) {
                                    Object service = XposedHelpers.getObjectField(param.thisObject, "mService");
                                    Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
                                    current = new SystemController(context, serviceHooks, load.classLoader, startupTrace, applicationHooks.coverage());
                                    controller = current;
                                }
                            }
                        }
                        String name = "";
                        try {
                            ComponentName component = (ComponentName) XposedHelpers.getObjectField(activity, "mActivityComponent");
                            if (component != null) name = component.getClassName();
                        } catch (Throwable ignored) {}
                        current.onForeground(pkg, user, Contract.CONTROL_ACTIVITY.equals(name), System.identityHashCode(activity));
                    } catch (Throwable error) {
                        if (!failureLogged) {
                            failureLogged = true;
                            XposedBridge.log("ClashAutoProxy: foreground hook incompatible: " + error);
                        }
                    }
                }
            }).isEmpty()) throw new IllegalStateException("Android task callback missing");
            XposedBridge.log("ClashAutoProxy: Android " + Build.VERSION.RELEASE + " foreground hook installed");
        } catch (Throwable error) {
            XposedBridge.log("ClashAutoProxy: unsupported framework; module disabled: " + error);
        }
    }

    private void installServiceHooks(ClassLoader loader) {
        try {
            Class<?> active = XposedHelpers.findClass("com.android.server.am.ActiveServices", loader);
            XC_MethodHook observer = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (controller == null || param.args.length == 0 || param.args[0] == null) return;
                        ComponentName name = (ComponentName) XposedHelpers.getObjectField(param.args[0], "name");
                        if (Contract.TUN_SERVICE.equals(name.getClassName()) || Contract.CORE_SERVICE.equals(name.getClassName()))
                            controller.onServiceChanged(name.getPackageName());
                    } catch (Throwable ignored) { /* Optional compatibility path; never affect AMS. */ }
                }
            };
            boolean foreground = !XposedBridge.hookAllMethods(active, "setServiceForegroundInnerLocked", observer).isEmpty();
            boolean teardown = !XposedBridge.hookAllMethods(active, "bringDownServiceLocked", observer).isEmpty();
            serviceHooks = foreground && teardown;
        } catch (Throwable ignored) { serviceHooks = false; }
    }
}
