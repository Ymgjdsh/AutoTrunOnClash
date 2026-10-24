package io.github.clashautoproxy.core;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class DecisionEngineTest {
    private final Set<String> apps = Set.of("example.video", "example.browser");
    @Test public void bothTargetAppsKeepTheProxyRequested() {
        assertTrue(DecisionEngine.wantsProxy(true, apps, "example.video", 0, true, true));
        assertTrue(DecisionEngine.wantsProxy(true, apps, "example.browser", 0, true, true));
        assertFalse(DecisionEngine.wantsProxy(true, apps, "example.launcher", 0, true, true));
    }
    @Test public void screenPolicyDoesNotOverrideDisabledOrUserIsolation() {
        assertFalse(DecisionEngine.wantsProxy(true, apps, "example.video", 0, false, true));
        assertTrue(DecisionEngine.wantsProxy(true, apps, "example.video", 0, false, false));
        assertFalse(DecisionEngine.wantsProxy(false, apps, "example.video", 0, true, false));
        assertFalse(DecisionEngine.wantsProxy(true, apps, "example.video", 10, true, false));
        assertFalse(DecisionEngine.wantsProxy(true, Set.of(), "example.video", 0, true, false));
    }
    @Test public void manualProxyIsPreservedUnlessUserExplicitlyOptsOut() {
        assertFalse(DecisionEngine.mayStop(false, true));
        assertTrue(DecisionEngine.mayStop(true, true));
        assertTrue(DecisionEngine.mayStop(false, false));
    }
}
