package io.github.clashautoproxy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.ArrayList;
import java.util.HashSet;

/** Only the module UID and system_server can cross this boundary. No world-readable files. */
public final class SettingsProvider extends ContentProvider {
    private Bundle status = new Bundle();
    @Override public boolean onCreate() { return true; }
    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != Process.myUid()) {
            throw new SecurityException("Only system_server and the settings app may use this provider");
        }
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        SharedPreferences prefs = getContext().getSharedPreferences("settings", 0);
        if ("config".equals(method)) {
            Bundle result = new Bundle();
            result.putBoolean("enabled", prefs.getBoolean("enabled", false));
            result.putStringArrayList("packages", new ArrayList<>(prefs.getStringSet("packages", new HashSet<>())));
            result.putString("proxyPackage", prefs.getString("proxyPackage", Contract.DEFAULT_PROXY));
            result.putBoolean("vpn", prefs.getBoolean("vpn", true));
            result.putBoolean("officialStart", prefs.getBoolean("officialStart", true));
            result.putInt("delay", prefs.getInt("delay", 3));
            result.putBoolean("protectManual", true);
            result.putBoolean("backgroundStopOnScreenOff", prefs.getBoolean("backgroundStopOnScreenOff", false));
            result.putBoolean("keepWhileRunning", prefs.getBoolean("keepWhileRunning", true));
            result.putBoolean("protectOtherVpn", prefs.getBoolean("protectOtherVpn", true));
            result.putBoolean("ignoreSystemUi", prefs.getBoolean("ignoreSystemUi", true));
            result.putLong("commandId", prefs.getLong("commandId", 0));
            result.putString("command", prefs.getString("command", ""));
            return result;
        }
        if ("report".equals(method) && Binder.getCallingUid() == Process.SYSTEM_UID) {
            status = extras == null ? new Bundle() : new Bundle(extras);
            getContext().getContentResolver().notifyChange(Contract.STATUS_URI, null);
            return new Bundle();
        }
        if ("status".equals(method)) return new Bundle(status);
        throw new IllegalArgumentException("Unknown method: " + method);
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { enforceCaller(); return null; }
    @Override public String getType(Uri u) { enforceCaller(); return "vnd.android.cursor.item/vnd.clashautoproxy"; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new SecurityException("Read/call only"); }
    @Override public int delete(Uri u, String s, String[] a) { throw new SecurityException("Read/call only"); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new SecurityException("Read/call only"); }
}
