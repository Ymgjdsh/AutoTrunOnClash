package io.github.clashautoproxy.core;

import java.util.function.Consumer;

/** Decode Android service results without treating a deferred request as an explicit refusal. */
public final class ServiceStartPolicy {
    private ServiceStartPolicy() {}
    public record Result(String packageName, String detail) {}
    public enum Outcome { REQUESTED, PENDING }
    public interface Route { Result start() throws Exception; }
    public static final class Unavailable extends Exception {
        public Unavailable(String reason, Throwable cause) { super(reason, cause); }
    }
    public static Outcome launch(String targetPackage, Route appIdentity, Route systemIdentity,
                                 Consumer<String> log) throws Exception {
        Result result;
        String route;
        try {
            route = "应用身份 / ActivityManagerInternal";
            log.accept("启动路径：" + route);
            result = appIdentity.start();
        } catch (Unavailable e) {
            // Only missing APIs permit fallback. A denial, null or runtime failure must not
            // trigger a second launch which can race a deferred start or hide the first error.
            route = "系统 Context（兼容回退）";
            log.accept("应用身份接口不可用：" + e.getMessage() + "；使用 " + route);
            result = systemIdentity.start();
        }
        if (result == null) {
            log.accept("启动返回 null：未确认，可能被限制或延迟处理；不重复发送，等待服务验证");
            return Outcome.PENDING;
        }
        String pkg = result.packageName();
        if ("!".equals(pkg)) throw new SecurityException(route + "：缺少服务权限：" + result.detail());
        if ("!!".equals(pkg)) throw new SecurityException(route + "：无法启动服务：" + result.detail());
        if ("?".equals(pkg)) throw new IllegalStateException(route + "：后台启动被拒绝：" + result.detail());
        if (!targetPackage.equals(pkg)) throw new IllegalStateException(route + "：返回非预期组件：" + pkg + "/" + result.detail());
        log.accept("启动请求已返回组件：" + pkg + "/" + result.detail() + "；等待实际运行验证");
        return Outcome.REQUESTED;
    }
}
