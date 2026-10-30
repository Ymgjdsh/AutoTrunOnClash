package io.github.clashautoproxy.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClashStartRouteTest {
    private int queries;
    private String available() { queries++; return "official-entry"; }
    private String mustNotQuery() { fail("direct mode must not inspect an official activity"); return null; }

    @Test public void availableEntryIsResolvedOnceAndPreserved() throws Exception {
        var route = ClashStartRoute.select(true, true, this::available);
        assertTrue(route.useOfficial()); assertEquals("official-entry", route.officialEntry());
        assertFalse(route.fellBack()); assertEquals(1, queries);
    }
    @Test public void missingEntrySelectsDirectServiceWithoutLaunchingAnything() throws Exception {
        var route = ClashStartRoute.select(true, true, () -> {
            queries++; throw new ClashStartRoute.EntryUnavailable("no matching START_CLASH");
        });
        assertFalse(route.useOfficial()); assertTrue(route.fellBack());
        assertNull(route.officialEntry()); assertEquals(1, queries);
    }
    @Test public void explicitDirectModeNeverQueriesUnavailableOfficialApi() throws Exception {
        var route = ClashStartRoute.select(true, false, this::mustNotQuery);
        assertFalse(route.useOfficial()); assertFalse(route.fellBack());
    }
    @Test public void coreModeNeverQueriesOfficialActivity() throws Exception {
        var route = ClashStartRoute.select(false, true, this::mustNotQuery);
        assertFalse(route.useOfficial()); assertFalse(route.fellBack());
    }
    @Test public void permissionDenialDoesNotSelectFallback() {
        SecurityException denied = new SecurityException("permission denied");
        assertSame(denied, assertThrows(SecurityException.class,
                () -> ClashStartRoute.select(true, true, () -> { throw denied; })));
    }
    @Test public void unrelatedRuntimeErrorDoesNotSelectFallback() {
        IllegalStateException failed = new IllegalStateException("package manager unavailable");
        assertSame(failed, assertThrows(IllegalStateException.class,
                () -> ClashStartRoute.select(true, true, () -> { throw failed; })));
    }
    @Test public void checkedQueryFailureDoesNotBecomeMissingEntry() {
        Exception failed = new Exception("query failed");
        assertSame(failed, assertThrows(Exception.class,
                () -> ClashStartRoute.select(true, true, () -> { throw failed; })));
    }
    @Test public void nullQueryResultIsAnErrorNotAConfirmedMissingEntry() {
        assertThrows(IllegalStateException.class, () -> ClashStartRoute.select(true, true, () -> null));
    }
}
