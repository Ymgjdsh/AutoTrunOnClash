package io.github.clashautoproxy.core;

import java.util.Set;

/** Pure decision policy; no Android objects, timers, I/O or polling. */
public final class DecisionEngine {
    private DecisionEngine() {}
    public static boolean wantsProxy(boolean enabled, Set<String> selected, String foreground,
                                     int userId, boolean interactive, boolean stopOnScreenOff) {
        return enabled && userId == 0 && selected.contains(foreground)
                && (interactive || !stopOnScreenOff);
    }
    public static boolean mayStop(boolean owned, boolean protectManual) {
        return owned || !protectManual;
    }
}
