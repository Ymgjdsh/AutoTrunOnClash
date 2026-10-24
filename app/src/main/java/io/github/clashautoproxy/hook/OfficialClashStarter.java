package io.github.clashautoproxy.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import io.github.clashautoproxy.Contract;

/** Uses CMFA's exported START_CLASH action, never TOGGLE or a fabricated component. */
final class OfficialClashStarter {
    static ComponentName resolve(Context context, String pkg) throws Exception {
        return resolve(context, pkg, "START_CLASH");
    }
    private static ComponentName resolve(Context context, String pkg, String action) throws Exception {
        Intent request = new Intent(pkg + ".action." + action).setPackage(pkg);
        for (ResolveInfo resolved : context.getPackageManager().queryIntentActivities(request,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY))) {
            ActivityInfo info = resolved.activityInfo;
            if (info != null && pkg.equals(info.packageName) && Contract.CONTROL_ACTIVITY.equals(info.name)
                    && info.exported && info.enabled && info.applicationInfo.enabled) {
                return new ComponentName(pkg, info.name);
            }
        }
        throw new IllegalStateException("当前 Clash 版本未提供可用的官方 " + action + " 入口，请检查 CMFA 版本。");
    }
    static void start(Context context, String pkg) throws Exception {
        request(context, pkg, "START_CLASH");
    }
    static void stop(Context context, String pkg) throws Exception {
        request(context, pkg, "STOP_CLASH");
    }
    private static void request(Context context, String pkg, String action) throws Exception {
        ComponentName activity = resolve(context, pkg, action);
        Intent request = new Intent(pkg + ".action." + action).setComponent(activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        long identity = Binder.clearCallingIdentity();
        try { context.startActivity(request); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
}
