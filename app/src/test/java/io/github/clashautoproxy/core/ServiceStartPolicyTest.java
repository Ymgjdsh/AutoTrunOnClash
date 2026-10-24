package io.github.clashautoproxy.core;

import org.junit.Test;
import java.util.ArrayList;
import static org.junit.Assert.*;

public class ServiceStartPolicyTest {
    private final ArrayList<String> log = new ArrayList<>();
    private int legacyCalls;
    private ServiceStartPolicy.Result legacy() { legacyCalls++; return new ServiceStartPolicy.Result("clash", "TunService"); }
    @Test public void successUsesTargetIdentityOnly() throws Exception {
        assertEquals(ServiceStartPolicy.Outcome.REQUESTED, ServiceStartPolicy.launch("clash", () -> new ServiceStartPolicy.Result("clash", "TunService"), this::legacy, log::add));
        assertEquals(0, legacyCalls);
    }
    @Test public void nullIsPendingAndDoesNotDuplicateDeferredStart() throws Exception {
        assertEquals(ServiceStartPolicy.Outcome.PENDING, ServiceStartPolicy.launch("clash", () -> null, this::legacy, log::add));
        assertEquals(0, legacyCalls); assertTrue(log.get(log.size()-1).contains("null"));
    }
    @Test public void unavailableApiFallsBackOnce() throws Exception {
        assertEquals(ServiceStartPolicy.Outcome.REQUESTED, ServiceStartPolicy.launch("clash", () -> { throw new ServiceStartPolicy.Unavailable("missing", null); }, this::legacy, log::add));
        assertEquals(1, legacyCalls);
    }
    @Test public void realFailureDoesNotFallBack() {
        assertThrows(SecurityException.class, () -> ServiceStartPolicy.launch("clash", () -> { throw new SecurityException("vendor denial"); }, this::legacy, log::add));
        assertEquals(0, legacyCalls);
    }
    @Test public void explicitPermissionDenialPreservesReason() {
        Exception e = assertThrows(SecurityException.class, () -> ServiceStartPolicy.launch("clash", () -> new ServiceStartPolicy.Result("!", "BIND_VPN_SERVICE"), this::legacy, log::add));
        assertTrue(e.getMessage().contains("BIND_VPN_SERVICE")); assertEquals(0, legacyCalls);
    }
    @Test public void explicitServiceDenialPreservesReason() {
        assertThrows(SecurityException.class, () -> ServiceStartPolicy.launch("clash", () -> new ServiceStartPolicy.Result("!!", "restricted"), this::legacy, log::add));
        assertEquals(0, legacyCalls);
    }
    @Test public void backgroundDenialPreservesReason() {
        Exception e = assertThrows(IllegalStateException.class, () -> ServiceStartPolicy.launch("clash", () -> new ServiceStartPolicy.Result("?", "app is in background"), this::legacy, log::add));
        assertTrue(e.getMessage().contains("app is in background")); assertEquals(0, legacyCalls);
    }
    @Test public void unexpectedComponentIsNotSuccess() {
        assertThrows(IllegalStateException.class, () -> ServiceStartPolicy.launch("clash", () -> new ServiceStartPolicy.Result("wrong", "Other"), this::legacy, log::add));
    }
    @Test public void fallbackNullAlsoWaitsForVerification() throws Exception {
        assertEquals(ServiceStartPolicy.Outcome.PENDING, ServiceStartPolicy.launch("clash", () -> { throw new ServiceStartPolicy.Unavailable("missing", null); }, () -> null, log::add));
    }
}
