package io.github.clashautoproxy.core;

import org.junit.Test;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class AppPresencePolicyTest {
    private final Set<String> selected = Set.of("a", "b");
    private AppPresencePolicy.AppProcess app(String name, boolean active, String... packages) {
        return new AppPresencePolicy.AppProcess(0,123,active,Set.of(packages),name);
    }
    @Test public void liveCachedAppWithRecentTaskCounts(){ assertEquals(Set.of("a"),AppPresencePolicy.running(selected,Set.of("a"),List.of(app("a",false,"a")))); }
    @Test public void removedTaskWithOnlyCachedRemnantDoesNotCount(){ assertTrue(AppPresencePolicy.running(selected,Set.of(),List.of(app("a",false,"a"))).isEmpty()); }
    @Test public void realBackgroundServiceCountsWithoutRecentTask(){ assertEquals(Set.of("a"),AppPresencePolicy.running(selected,Set.of(),List.of(app("a:music",true,"a")))); }
    @Test public void recentTaskWithDeadProcessDoesNotCount(){ assertTrue(AppPresencePolicy.running(selected,Set.of("a"),List.of()).isEmpty()); }
    @Test public void secondaryProcessCounts(){ assertEquals(Set.of("b"),AppPresencePolicy.running(selected,Set.of("b"),List.of(app("b:worker",false)))); }
    @Test public void similarlyNamedPackageDoesNotCount(){ assertTrue(AppPresencePolicy.running(selected,Set.of("a"),List.of(app("ab:worker",true,"ab"))).isEmpty()); }
    @Test public void unrelatedAppsAreExcluded(){ assertTrue(AppPresencePolicy.running(selected,Set.of("x"),List.of(app("x",true,"x"))).isEmpty()); }
    @Test public void secondaryUserIsExcluded(){ assertTrue(AppPresencePolicy.running(selected,Set.of("a"),List.of(new AppPresencePolicy.AppProcess(10,123,true,Set.of("a"),"a"))).isEmpty()); }
    @Test public void pendingProcessWithoutPidDoesNotCount(){ assertTrue(AppPresencePolicy.running(selected,Set.of("a"),List.of(new AppPresencePolicy.AppProcess(0,0,true,Set.of("a"),"a"))).isEmpty()); }
    @Test public void allMatchingAppsAreKept(){ assertEquals(selected,AppPresencePolicy.running(selected,selected,List.of(app("a",false,"a"),app("b",false,"b")))); }
    @Test public void packageListSupportsRenamedSharedProcesses(){ assertEquals(Set.of("a"),AppPresencePolicy.running(selected,Set.of(),List.of(app("custom",true,"a","x")))); }
    @Test public void duplicateProcessesDoNotDuplicateApps(){ assertEquals(Set.of("a"),AppPresencePolicy.running(selected,Set.of("a"),List.of(app("a",false,"a"),app("a:remote",true,"a")))); }
}
