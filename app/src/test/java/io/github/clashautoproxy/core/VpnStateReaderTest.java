package io.github.clashautoproxy.core;

import java.lang.reflect.InvocationTargetException;
import org.junit.Test;
import static org.junit.Assert.*;

public class VpnStateReaderTest {
    private static VpnStateReader.State none() { return new VpnStateReader.State(false, "", "", "networks", ""); }
    private static VpnStateReader.State active(String owner) { return new VpnStateReader.State(true, owner, "tun0", "networks", ""); }
    private static VpnStateReader.State unavailable() { throw new SecurityException("Unauthorized Caller"); }
    private static VpnStateReader.State unexpected() { fail("unnecessary fallback"); return null; }

    private static class Config { String user = "clash", interfaze = "tun0"; }
    private static class Vpn { Object mConfig; }
    public static class Vpns {
        Vpn vpn;
        public Object get(int userId) { assertTrue(Thread.holdsLock(this)); assertEquals(0, userId); return vpn; }
    }
    private static class Manager { final Vpns mVpns = new Vpns(); }
    private static class OemManager extends Manager {}

    @Test public void localNullConfigIsAValidEmptySnapshot() throws Exception {
        Manager manager = new Manager(); manager.mVpns.vpn = new Vpn();
        assertFalse(VpnStateReader.local(manager, 0).present());
    }
    @Test public void noUserRecordIsAValidEmptySnapshot() throws Exception {
        assertFalse(VpnStateReader.local(new Manager(), 0).present());
    }
    @Test public void readsInheritedOemFieldsAndOwner() throws Exception {
        OemManager manager = new OemManager(); manager.mVpns.vpn = new Vpn(); manager.mVpns.vpn.mConfig = new Config();
        VpnStateReader.State state = VpnStateReader.local(manager, 0);
        assertTrue(state.present()); assertEquals("clash", state.ownerPackage()); assertEquals("tun0", state.interfaceName());
    }
    @Test public void localSnapshotDoesNotInvokePermissionCheckedApi() throws Exception {
        VpnStateReader.State result = VpnStateReader.read(() -> VpnStateReader.local(new Manager(), 0),
                VpnStateReaderTest::unexpected, VpnStateReaderTest::unexpected);
        assertFalse(result.present()); assertEquals("", result.detail());
    }
    @Test public void localPermissionFailureCanUseApi() throws Exception {
        VpnStateReader.State result = VpnStateReader.read(VpnStateReaderTest::unavailable,
                () -> active("clash"), VpnStateReaderTest::unexpected);
        assertTrue(VpnSafetyPolicy.connected("clash", result)); assertTrue(result.detail().contains("Unauthorized Caller"));
    }
    @Test public void unavailableConfigApiDoesNotBlockStartWhenNoVpnExists() throws Exception {
        VpnStateReader.State result = VpnStateReader.read(VpnStateReaderTest::unavailable,
                VpnStateReaderTest::unavailable, VpnStateReaderTest::none);
        assertFalse(VpnSafetyPolicy.connected("clash", result));
        VpnSafetyPolicy.requireSafeStart("clash", result);
        assertEquals("networks", result.source()); assertTrue(result.detail().contains("SecurityException"));
    }
    @Test public void unredactedTargetOwnerRemainsRecognizedInFallback() throws Exception {
        VpnStateReader.State state = VpnStateReader.read(VpnStateReaderTest::unavailable,
                VpnStateReaderTest::unavailable, () -> active("clash"));
        assertTrue(VpnSafetyPolicy.connected("clash", state));
    }
    @Test public void redactedOwnerDoesNotAllowStartupOrFalseStopConfirmation() throws Exception {
        VpnStateReader.State state = VpnStateReader.read(VpnStateReaderTest::unavailable,
                VpnStateReaderTest::unavailable, () -> active(""));
        assertThrows(IllegalStateException.class, () -> VpnSafetyPolicy.requireSafeStart("clash", state));
        assertThrows(IllegalStateException.class, () -> VpnSafetyPolicy.connected("clash", state));
    }
    @Test public void anotherVpnIsStillProtectedAfterApiFailure() throws Exception {
        VpnStateReader.State state = VpnStateReader.read(VpnStateReaderTest::unavailable,
                VpnStateReaderTest::unavailable, () -> active("other"));
        assertFalse(VpnSafetyPolicy.connected("clash", state));
        assertThrows(IllegalStateException.class, () -> VpnSafetyPolicy.requireSafeStart("clash", state));
    }
    @Test public void allFailedReadsAreUnknownNotAnEmptyVpn() {
        Exception error = assertThrows(IllegalStateException.class, () -> VpnStateReader.read(
                VpnStateReaderTest::unavailable, VpnStateReaderTest::unavailable, VpnStateReaderTest::unavailable));
        assertTrue(error.getMessage().contains("Unauthorized Caller"));
    }
    @Test public void nullResultsDoNotMeanNoVpn() {
        assertThrows(IllegalStateException.class, () -> VpnStateReader.read(() -> null, () -> null, () -> null));
    }
    @Test public void incompatibleLocalShapeUsesNetworkFallback() throws Exception {
        VpnStateReader.State state = VpnStateReader.read(() -> VpnStateReader.local(new Object(), 0),
                VpnStateReaderTest::unavailable, VpnStateReaderTest::none);
        assertFalse(state.present()); assertTrue(state.detail().contains("mVpns"));
    }
    @Test public void configFieldFailureIsNotTreatedAsEmpty() {
        assertThrows(NoSuchFieldException.class, () -> VpnStateReader.config(new Object(), "test"));
    }
    @Test public void fullFailureCauseIsPreservedAndBounded() {
        String detail = VpnStateReader.describe(new IllegalStateException("getVpnConfig",
                new InvocationTargetException(new SecurityException("Unauthorized Caller"))));
        assertTrue(detail.contains("getVpnConfig")); assertTrue(detail.contains("SecurityException: Unauthorized Caller"));
        assertTrue(VpnStateReader.describe(new RuntimeException("x".repeat(3000))).length() <= 1401);
    }
}
