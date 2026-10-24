package io.github.clashautoproxy.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A task-backed cached process or a real background worker counts; empty remnants do not. */
public final class AppPresencePolicy {
    private AppPresencePolicy() {}
    public record AppProcess(int userId, int pid, boolean active, Set<String> packages, String name) {
        public AppProcess { packages = Set.copyOf(packages); }
    }
    public static Set<String> running(Set<String> selected, Set<String> tasks, List<AppProcess> processes) {
        Set<String> result = new HashSet<>();
        for (AppProcess process : processes) {
            if (process.userId() != 0 || process.pid() <= 0) continue;
            for (String pkg : selected) {
                boolean belongs = process.packages().contains(pkg) || pkg.equals(process.name())
                        || process.name() != null && process.name().startsWith(pkg + ":");
                if (belongs && (process.active() || tasks.contains(pkg))) result.add(pkg);
            }
        }
        return Set.copyOf(result);
    }
}
