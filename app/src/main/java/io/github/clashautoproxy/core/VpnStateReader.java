package io.github.clashautoproxy.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Read-only snapshots. An unavailable API is never interpreted as an absent VPN. */
public final class VpnStateReader {
    private VpnStateReader() {}
    public record State(boolean present, String ownerPackage, String interfaceName,
                        String source, String detail) {}
    public interface Read { State read() throws Exception; }

    public static State read(Read local, Read api, Read networks) throws Exception {
        StringBuilder failures = new StringBuilder();
        for (Read read : new Read[]{local, api, networks}) {
            try {
                State state = read.read();
                if (state == null) throw new IllegalStateException("VPN 查询未返回状态");
                return new State(state.present(), state.ownerPackage(), state.interfaceName(),
                        state.source(), failures.toString());
            } catch (Exception | LinkageError failure) {
                if (failures.length() > 0) failures.append("; ");
                failures.append(describe(failure));
            }
        }
        throw new IllegalStateException("无法确认系统 VPN 状态：" + failures);
    }

    /** AOSP 15: VpnManagerService guards mVpns by itself; Vpn guards mConfig by itself. */
    public static State local(Object manager, int userId) throws Exception {
        Object vpns = field(manager, "mVpns");
        if (vpns == null) throw new IllegalStateException("VPN 管理表不可用");
        synchronized (vpns) {
            Method get = vpns.getClass().getMethod("get", int.class);
            get.setAccessible(true);
            Object vpn = get.invoke(vpns, userId);
            if (vpn == null) return config(null, "系统本地只读快照");
            synchronized (vpn) { return config(field(vpn, "mConfig"), "系统本地只读快照"); }
        }
    }

    public static State config(Object config, String source) throws Exception {
        if (config == null) return new State(false, "", "", source, "");
        Object owner = field(config, "user"), iface = field(config, "interfaze");
        return new State(true, owner == null ? "" : owner.toString(),
                iface == null ? "" : iface.toString(), source, "");
    }

    private static Object field(Object object, String name) throws Exception {
        if (object == null) throw new IllegalStateException("VPN 对象为空：" + name);
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) { /* OEM subclasses may inherit the field. */ }
        }
        throw new NoSuchFieldException(object.getClass().getName() + "." + name);
    }

    public static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int depth = 0; error != null && depth < 6 && seen.add(error); depth++, error = error.getCause()) {
            if (depth > 0) text.append(" <- ");
            text.append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        }
        return text.length() > 1400 ? text.substring(0, 1400) + "…" : text.toString();
    }
}
