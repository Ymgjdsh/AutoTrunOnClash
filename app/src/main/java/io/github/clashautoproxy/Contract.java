package io.github.clashautoproxy;

import android.net.Uri;

public final class Contract {
    private Contract() {}
    public static final String PACKAGE = "io.github.clashautoproxy";
    public static final String DEFAULT_PROXY = "com.github.metacubex.clash.meta";
    public static final Uri CONFIG_URI = Uri.parse("content://" + PACKAGE + ".settings/config");
    public static final Uri STATUS_URI = Uri.parse("content://" + PACKAGE + ".settings/status");
    public static final Uri PING_URI = Uri.parse("content://" + PACKAGE + ".settings/ping");
    public static final String TUN_SERVICE = "com.github.kr328.clash.service.TunService";
    public static final String CORE_SERVICE = "com.github.kr328.clash.service.ClashService";
    public static final String CONTROL_ACTIVITY = "com.github.kr328.clash.ExternalControlActivity";
}
