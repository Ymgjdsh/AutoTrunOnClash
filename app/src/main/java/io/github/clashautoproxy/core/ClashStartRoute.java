package io.github.clashautoproxy.core;

/** Only a positively identified missing entry permits the service-start fallback. */
public final class ClashStartRoute {
    private ClashStartRoute() {}

    public static class EntryUnavailable extends IllegalStateException {
        public EntryUnavailable(String message) { super(message); }
    }
    public interface Resolver<T> { T resolve() throws Exception; }
    public record Choice<T>(T officialEntry, boolean fellBack) {
        public boolean useOfficial() { return officialEntry != null; }
    }
    public static <T> Choice<T> select(boolean vpnMode, boolean preferOfficial, Resolver<T> resolver) throws Exception {
        if (!vpnMode || !preferOfficial) return new Choice<>(null, false);
        try {
            T entry = resolver.resolve();
            if (entry == null) throw new IllegalStateException("客户端入口查询未返回有效结果");
            return new Choice<>(entry, false);
        } catch (EntryUnavailable missing) {
            return new Choice<>(null, true);
        }
    }
}
