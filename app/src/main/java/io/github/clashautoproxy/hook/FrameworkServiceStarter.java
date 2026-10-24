package io.github.clashautoproxy.hook;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Binder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedHelpers;
import io.github.clashautoproxy.core.ServiceStartPolicy;

/** System-server-only, explicit CMFA service start attributed to its actual package/UID. */
final class FrameworkServiceStarter {
    private final ClassLoader loader;
    private Object manager, privileges;
    private Method start;
    FrameworkServiceStarter(ClassLoader loader) { this.loader = loader; }

    ComponentName start(int uid, String packageName, Intent intent) throws Exception {
        if (start == null) {
            try {
                Class<?> localServices = XposedHelpers.findClass("com.android.server.LocalServices", loader);
                Class<?> internal = XposedHelpers.findClass("android.app.ActivityManagerInternal", loader);
                Class<?> bg = XposedHelpers.findClass("android.app.BackgroundStartPrivileges", loader);
                Object service = XposedHelpers.callStaticMethod(localServices, "getService", internal);
                if (service == null) throw new IllegalStateException("ActivityManagerInternal 尚不可用");
                Method method = XposedHelpers.findMethodExact(internal, "startServiceInPackage",
                        int.class, Intent.class, String.class, boolean.class, String.class,
                        String.class, int.class, bg);
                // One foreground-service request only. This grants no background Activity launch.
                Object foregroundOnly = XposedHelpers.getStaticObjectField(bg, "ALLOW_FGS");
                manager = service; privileges = foregroundOnly; start = method;
            } catch (Throwable error) {
                throw new ServiceStartPolicy.Unavailable(error.getClass().getSimpleName() + ": " + error.getMessage(), error);
            }
        }
        long identity = Binder.clearCallingIdentity();
        try {
            // Matches Android 15 ActivityManagerInternal.startServiceInPackage, the path also
            // used by PendingIntentRecord. No foreign IApplicationThread/package mismatch.
            return (ComponentName) start.invoke(manager, uid, intent, null, true,
                    packageName, null, 0, privileges);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("系统内部启动异常：" + cause, cause);
        } finally { Binder.restoreCallingIdentity(identity); }
    }
}
