package io.github.clashautoproxy.core;

/** A redacted owner must stay UNKNOWN, never be mistaken for another VPN or no VPN. */
public final class VpnSafetyPolicy {
    private VpnSafetyPolicy() {}
    public enum Owner { NONE, TARGET, OTHER, UNKNOWN }
    public static Owner owner(String target, boolean hasConfig, String packageName) {
        if (!hasConfig) return Owner.NONE;
        if (packageName == null || packageName.isEmpty()) return Owner.UNKNOWN;
        return target.equals(packageName) ? Owner.TARGET : Owner.OTHER;
    }
    public static boolean connected(String target, VpnStateReader.State state) {
        Owner owner = owner(target, state.present(), state.ownerPackage());
        if (owner == Owner.UNKNOWN) throw new IllegalStateException("系统 VPN 所属应用未知，未确认代理已关闭；请查看 VPN 读取路径和兼容诊断");
        return owner == Owner.TARGET;
    }
    public static void requireSafeStart(String target, VpnStateReader.State state) {
        Owner owner = owner(target, state.present(), state.ownerPackage());
        if (owner == Owner.OTHER) throw new IllegalStateException("其他 VPN 正在运行（" + state.ownerPackage() + "），已按保护设置取消启动");
        if (owner == Owner.UNKNOWN) throw new IllegalStateException("无法确认当前 VPN 所属应用，已保守取消启动；请查看 VPN 读取路径和兼容诊断");
    }
}
