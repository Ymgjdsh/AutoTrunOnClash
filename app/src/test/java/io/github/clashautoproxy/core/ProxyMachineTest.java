package io.github.clashautoproxy.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import static org.junit.Assert.*;

public class ProxyMachineTest {
    private static final ProxyMachine.Target VPN = new ProxyMachine.Target("clash", "TunService");
    private static final ProxyMachine.Target CORE = new ProxyMachine.Target("clash", "CoreService");
    private static final Set<String> APPS = Set.of("a", "b");
    private static class Clock implements ProxyMachine.Scheduler {
        record Job(long time, long sequence, Object key, Runnable action) implements Comparable<Job> {
            @Override public int compareTo(Job b) { int d = Long.compare(time,b.time); return d != 0 ? d : Long.compare(sequence,b.sequence); }
        }
        long time, sequence;
        Map<Object,Job> latest = new HashMap<>();
        PriorityQueue<Job> jobs = new PriorityQueue<>();
        @Override public long now() { return time; }
        @Override public void after(Object key,long delay,Runnable task) { Job job = new Job(time+delay,sequence++,key,task); latest.put(key,job);jobs.add(job); }
        @Override public void cancel(Object key) { latest.remove(key); }
        void advance(long millis) {
            long end=time+millis;int guard=0;
            while(!jobs.isEmpty() && jobs.peek().time<=end) {
                Job job=jobs.remove();time=job.time;
                if(latest.get(job.key)==job) { latest.remove(job.key);job.action.run();if(++guard>1000)fail("unbounded retry loop"); }
            }
            time=end;
        }
    }
    private static class Service implements ProxyMachine.Gateway {
        Map<String,ProxyMachine.Service> live=new HashMap<>();int starts,stops,timeouts;long serial=100;
        boolean failStart,failStop,neverStarts,neverStops,delayedStop;
        boolean presenceFail,vpnAlive,keepTunnel,rejectOwnership;
        boolean vpnFallback, vpnUnknown;
        String vpnOwner = "clash";
        int fallbackStops;
        Set<String> activeApps = Set.of();
        @Override public ProxyMachine.Service running(String pkg) { return live.get(pkg); }
        @Override public void start(ProxyMachine.Target t,boolean protect) throws Exception {
            if(vpnFallback && protect) VpnSafetyPolicy.requireSafeStart(t.packageName(), vpnSnapshot());
            starts++;if(failStart)throw new Exception("permission denied");if(!neverStarts)live.put(t.packageName(),new ProxyMachine.Service(t,++serial));
        }
        @Override public void stop(ProxyMachine.Target t) throws Exception { stops++;if(failStop)throw new Exception("stop denied");if(!neverStops&&!delayedStop)live.remove(t.packageName());if(!keepTunnel&&!neverStops)vpnAlive=false; }
        void manual(ProxyMachine.Target t) { live.put(t.packageName(),new ProxyMachine.Service(t,++serial)); }
        @Override public void startTimedOut(ProxyMachine.Target target) { timeouts++; }
        @Override public Set<String> runningSelected(Set<String> selected) throws Exception {
            if(presenceFail) throw new Exception("presence unavailable");
            Set<String> result = new java.util.HashSet<>(activeApps);result.retainAll(selected);return result;
        }
        private VpnStateReader.State vpnSnapshot() throws Exception {
            return VpnStateReader.read(() -> { throw new NoSuchFieldException("mVpns"); },
                    () -> { throw new SecurityException("getVpnConfig: Unauthorized Caller"); },
                    () -> {
                        if(vpnUnknown)throw new IllegalStateException("network query unavailable");
                        return new VpnStateReader.State(vpnAlive,vpnAlive?vpnOwner:"","","networks","");
                    });
        }
        @Override public boolean connected(ProxyMachine.Target target) throws Exception {
            return target.equals(VPN) && (vpnFallback ? VpnSafetyPolicy.connected(target.packageName(),vpnSnapshot()) : vpnAlive);
        }
        @Override public boolean stopOwned(ProxyMachine.Service expected) throws Exception {
            if(rejectOwnership)return false;stop(expected.target());return true;
        }
        @Override public void retryStop(ProxyMachine.Service expected){fallbackStops++;}
    }
    private final Clock clock=new Clock();private final Service service=new Service();
    private ProxyMachine.Status status;private final ArrayList<String> logs=new ArrayList<>();
    private final ProxyMachine machine=new ProxyMachine(service,clock,new ProxyMachine.Listener(){
        @Override public void status(ProxyMachine.Status s){status=s;}
        @Override public void log(String s){logs.add(s);}
    });
    private ProxyMachine.Config config(boolean enabled,int delay,boolean protect,boolean screen){return new ProxyMachine.Config(enabled,APPS,VPN,delay,protect,screen,true,true);}
    private void ready(){machine.configure(config(true,3,true,true));}
    private void enter(){machine.foreground("a",0);clock.advance(1000);}

    @Test public void targetToTargetDoesNotRestart(){ready();enter();machine.foreground("b",0);clock.advance(10000);assertEquals(1,service.starts);assertEquals(0,service.stops);}
    @Test public void exitClosesOnlyAfterDeadline(){ready();enter();machine.foreground("home",0);clock.advance(2999);assertEquals(0,service.stops);clock.advance(1);assertEquals(1,service.stops);clock.advance(300);assertFalse(status.running());}
    @Test public void reentryCancelsPendingStop(){ready();enter();machine.foreground("home",0);clock.advance(2000);machine.foreground("b",0);clock.advance(10000);assertEquals(0,service.stops);assertEquals("OWNED",status.code());}
    @Test public void nonTargetsDoNotExtendExitDeadline(){ready();enter();machine.foreground("home",0);clock.advance(2000);machine.foreground("other",0);clock.advance(1000);assertEquals(1,service.stops);}
    @Test public void zeroDelayClosesImmediately(){machine.configure(config(true,0,true,true));enter();machine.foreground("home",0);assertEquals(1,service.stops);}
    @Test public void disableReleasesOwnedImmediately(){ready();enter();machine.configure(config(false,3,true,true));assertEquals(1,service.stops);clock.advance(1000);assertEquals("DISABLED",status.code());}
    @Test public void existingManualServiceIsNotClaimed(){service.manual(VPN);ready();enter();machine.foreground("home",0);clock.advance(10000);assertEquals(0,service.starts);assertEquals(0,service.stops);assertFalse(status.owned());}
    @Test public void explicitStrictPolicyCanCloseManual(){service.manual(VPN);machine.configure(config(true,0,false,true));machine.foreground("home",0);clock.advance(300);assertTrue(service.stops>=1);}
    @Test public void screenOffStopsAndUnlockRestores(){ready();enter();machine.screen(false);clock.advance(300);assertEquals(1,service.stops);machine.screen(true);clock.advance(1000);assertEquals(2,service.starts);}
    @Test public void optionalScreenOffKeepPreservesProxy(){machine.configure(config(true,3,true,false));enter();machine.screen(false);clock.advance(10000);assertEquals(0,service.stops);}
    @Test public void secondaryUserCannotStart(){ready();machine.foreground("a",10);clock.advance(10000);assertEquals(0,service.starts);}
    @Test public void switchingUserReleasesPrimaryOwnedProxy(){ready();enter();machine.foreground("a",10);clock.advance(3300);assertEquals(1,service.stops);}
    @Test public void transientSystemPermissionUiDoesNotInterrupt(){ready();enter();machine.foreground("com.android.permissioncontroller",0);clock.advance(10000);assertEquals(0,service.stops);machine.foreground("home",0);clock.advance(3300);assertEquals(1,service.stops);}
    @Test public void removingSelectedPackageReleasesIt(){ready();enter();machine.configure(new ProxyMachine.Config(true,Set.of("b"),VPN,3,true,true,true,true));clock.advance(3300);assertEquals(1,service.stops);}
    @Test public void failedStartupIsBoundedAndDoesNotFightUser(){service.neverStarts=true;ready();machine.foreground("a",0);clock.advance(60000);assertEquals(1,service.starts);assertEquals("ERROR",status.code());machine.serviceChanged();machine.foreground("b",0);clock.advance(60000);assertEquals(1,service.starts);}
    @Test public void failedStartupCanRecoverAfterLeaving(){service.failStart=true;ready();machine.foreground("a",0);service.failStart=false;machine.foreground("home",0);machine.foreground("a",0);clock.advance(1000);assertEquals(2,service.starts);assertEquals("OWNED",status.code());}
    @Test public void manualStopDuringTargetSessionDoesNotRestart(){ready();enter();service.live.clear();machine.serviceChanged();clock.advance(10000);assertEquals(1,service.starts);assertEquals("ERROR",status.code());machine.foreground("home",0);machine.foreground("a",0);assertEquals(2,service.starts);}
    @Test public void replacedServiceInstanceBecomesManual(){ready();enter();service.manual(VPN);machine.serviceChanged();machine.foreground("home",0);clock.advance(10000);assertEquals(0,service.stops);assertFalse(status.owned());}
    @Test public void testLeaseWorksWhileAutomationDisabled(){machine.configure(config(false,3,true,true));machine.testStart();clock.advance(9999);assertEquals(0,service.stops);clock.advance(301);assertEquals(1,service.stops);assertFalse(status.owned());}
    @Test public void testNeverClaimsExistingManualProxy(){service.manual(VPN);machine.configure(config(false,3,true,true));machine.testStart();clock.advance(20000);assertEquals(0,service.stops);assertFalse(status.owned());}
    @Test public void explicitReleaseNeverStopsManualEvenInStrictMode(){service.manual(VPN);machine.configure(config(true,15,false,true));machine.foreground("a",0);machine.releaseOwned();assertEquals(0,service.stops);}
    @Test public void stopFailureIsReportedInsteadOfFakeSuccess(){ready();enter();service.neverStops=true;machine.foreground("home",0);clock.advance(20000);assertEquals("ERROR",status.code());assertTrue(status.owned());assertTrue(status.running());int attempts=service.stops;clock.advance(60000);assertEquals(attempts,service.stops);}
    @Test public void reentryWaitsForActualShutdownBeforeStarting(){ready();enter();service.delayedStop=true;machine.foreground("home",0);clock.advance(3000);machine.foreground("a",0);assertEquals(1,service.starts);service.live.clear();clock.advance(300);assertEquals(2,service.starts);}
    @Test public void changingModeReleasesOldOwnedBeforeNewStart(){ready();enter();machine.configure(new ProxyMachine.Config(true,APPS,CORE,3,true,true,true,true));assertEquals(1,service.stops);clock.advance(300);assertEquals(2,service.starts);assertEquals(CORE,service.live.get("clash").target());}
    @Test public void changingDelayUsesOriginalExitTime(){ready();enter();machine.foreground("home",0);clock.advance(2000);machine.configure(config(true,5,true,true));clock.advance(2999);assertEquals(0,service.stops);clock.advance(1);assertEquals(1,service.stops);}
    @Test public void releaseCancelsOldStartupVerification(){ready();machine.foreground("a",0);machine.releaseOwned();clock.advance(20000);assertEquals(1,service.starts);assertFalse(status.owned());assertNotEquals("OWNED",status.code());}
    @Test public void privacySafeDefaultRequiresSelectedApp(){ready();machine.foreground("unrelated",0);clock.advance(60000);assertEquals(0,service.starts);assertEquals(0,service.stops);}
    @Test public void explicitReleaseRetainsStopException(){ready();enter();service.failStop=true;machine.releaseOwned();assertEquals("ERROR",status.code());assertTrue(status.owned());assertTrue(status.error().contains("stop denied"));}
    @Test public void explicitReleaseCanRetryCloseFailure(){ready();enter();service.failStop=true;machine.releaseOwned();service.failStop=false;machine.releaseOwned();clock.advance(300);assertFalse(status.owned());assertFalse(status.running());}
    @Test public void disabledPingReportsManualServiceWithoutTakingOwnership(){machine.configure(config(false,3,true,true));service.manual(VPN);machine.serviceChanged();assertTrue(status.running());assertFalse(status.owned());assertEquals(0,service.stops);}
    @Test public void deferredRequestCanBecomeRunningWithoutDuplicateLaunch(){
        service.neverStarts=true;ready();machine.foreground("a",0);
        clock.advance(2000);assertEquals("STARTING",status.code());assertEquals(1,service.starts);
        service.manual(VPN);machine.serviceChanged();clock.advance(1000);
        assertEquals("OWNED",status.code());assertTrue(status.owned());assertEquals(1,service.starts);
        assertTrue(logs.stream().anyMatch(line -> line.contains("已确认服务运行")));
    }
    @Test public void deferredRequestNeverBecomesSuccessWithoutAService(){
        service.neverStarts=true;ready();machine.foreground("a",0);clock.advance(4999);
        assertEquals("STARTING",status.code());clock.advance(1);
        assertEquals("ERROR",status.code());assertFalse(status.owned());assertFalse(status.running());assertEquals(1,service.starts);
        assertEquals(1,service.timeouts);
    }
    @Test public void officialControlTransitionDoesNotStopWithZeroExitDelay(){
        machine.configure(config(true,0,true,true)); machine.foreground("a",0);
        machine.controlForeground("clash",0); clock.advance(100);
        assertEquals(0,service.stops); machine.foreground("a",0); clock.advance(6000);
        assertEquals(1,service.starts); assertEquals(0,service.stops); assertTrue(status.owned());
    }
    @Test public void stuckOfficialControlPageExpires(){
        machine.configure(config(true,0,true,true)); machine.foreground("a",0);
        machine.controlForeground("clash",0); clock.advance(1499); assertEquals(0,service.stops);
        clock.advance(1); assertEquals(1,service.stops);
    }
    @Test public void ordinaryClashPageStillCountsAsExit(){
        machine.configure(config(true,0,true,true)); enter(); machine.foreground("clash",0);
        assertEquals(1,service.stops);
    }
    @Test public void controlPageDoesNotIgnoreAnotherAppOrUser(){
        machine.configure(config(true,0,true,true)); enter(); machine.controlForeground("other",0);
        assertEquals(1,service.stops);
        clock.advance(300); machine.foreground("a",0); machine.controlForeground("clash",10);
        assertEquals(2,service.stops);
    }
    @Test public void screenOffStillStopsDuringOfficialTransition(){
        ready(); machine.foreground("a",0); machine.controlForeground("clash",0); machine.screen(false);
        assertEquals(1,service.stops); clock.advance(20000); assertEquals(1,service.starts);
    }
    @Test public void leavingForAnotherAppCancelsControlPageTimer(){
        ready(); machine.foreground("a",0); machine.controlForeground("clash",0);
        clock.advance(100); machine.foreground("b",0); clock.advance(6000);
        assertEquals("b",status.foreground()); assertEquals(0,service.stops);
    }
    @Test public void controlPageDoesNotKeepAnExistingManualProxyInStrictMode(){
        machine.foreground("a",0); service.manual(VPN); machine.configure(config(true,0,false,true));
        machine.controlForeground("clash",0); assertEquals(1,service.stops);
    }
    @Test public void wrongModeDuringStartupIsReportedAndNotOwned(){
        service.neverStarts=true; ready(); machine.foreground("a",0);
        service.manual(CORE); machine.serviceChanged(); clock.advance(6000);
        assertEquals("ERROR",status.code()); assertTrue(status.error().contains("模式与模块不一致"));
        assertFalse(status.owned()); assertEquals(0,service.stops);
    }
    @Test public void wrongModeDuringVerificationIsReportedAndNotOwned(){
        service.neverStarts=true; ready(); machine.foreground("a",0); service.manual(CORE); clock.advance(1000);
        assertEquals("ERROR",status.code()); assertFalse(status.owned());
        machine.foreground("home",0); clock.advance(10000); assertEquals(0,service.stops);
    }
    @Test public void allocatedServiceRecordIsNotAConfirmedForegroundService(){
        service.neverStarts=true; ready(); machine.foreground("a",0);
        service.live.put("clash",new ProxyMachine.Service(VPN,123,false)); machine.serviceChanged();
        assertEquals("STARTING",status.code()); assertFalse(status.running());
        clock.advance(5000); assertEquals("ERROR",status.code()); assertEquals(1,service.timeouts);
        assertFalse(status.owned()); assertEquals(1,service.starts);
    }
    @Test public void serviceBecomingForegroundIsConfirmedWithoutDuplicateStart(){
        service.neverStarts=true; ready(); machine.foreground("a",0);
        service.live.put("clash",new ProxyMachine.Service(VPN,123,false)); clock.advance(1000);
        assertEquals("STARTING",status.code());
        service.live.put("clash",new ProxyMachine.Service(VPN,123,true)); clock.advance(2000);
        assertEquals("OWNED",status.code()); assertTrue(status.running()); assertEquals(1,service.starts);
    }
    @Test public void existingUnconfirmedManualStartIsNotClaimedOrDuplicated(){
        service.live.put("clash",new ProxyMachine.Service(VPN,123,false)); ready(); machine.foreground("a",0);
        clock.advance(5000); assertEquals(0,service.starts); assertFalse(status.owned());
    }
    private ProxyMachine.Config backgroundConfig(boolean enabled,Set<String> apps,int delay){
        return new ProxyMachine.Config(enabled,apps,VPN,delay,true,false,true,true,true);
    }

    @Test public void unavailableVpnConfigStillStartsAndStopsAfterLastAppEnds() {
        service.vpnFallback=true; service.activeApps=Set.of("a");
        machine.configure(backgroundConfig(true,APPS,3)); enter();
        assertEquals(1,service.starts); assertTrue(status.owned());
        machine.foreground("home",0); clock.advance(6000);
        assertEquals(0,service.stops); assertEquals("OWNED",status.code());
        service.activeApps=Set.of(); machine.applicationsChanged(); clock.advance(3300);
        assertEquals(1,service.stops); assertFalse(status.owned()); assertEquals("WAITING",status.code());
    }
    @Test public void unavailableVpnConfigDoesNotTakeOverExistingManualTunnel() {
        service.vpnFallback=true; service.vpnAlive=true; service.activeApps=Set.of("a");
        machine.configure(backgroundConfig(true,APPS,3)); enter();
        assertEquals("MANUAL",status.code()); assertEquals(0,service.starts); assertFalse(status.owned());
        service.activeApps=Set.of(); machine.foreground("home",0); clock.advance(10000);
        assertEquals(0,service.stops); assertTrue(service.vpnAlive);
    }
    @Test public void unavailableVpnConfigNeverReplacesOtherVpn() {
        service.vpnFallback=true; service.vpnAlive=true; service.vpnOwner="other";
        ready(); enter();
        assertEquals(0,service.starts); assertEquals("ERROR",status.code()); assertEquals(0,service.stops);
    }
    @Test public void unavailableVpnConfigWithRedactedActiveOwnerFailsSafely() {
        service.vpnFallback=true; service.vpnAlive=true; service.vpnOwner="";
        ready(); enter();
        assertEquals(0,service.starts); assertEquals("ERROR",status.code()); assertEquals(0,service.stops);
    }
    @Test public void repeatedUnavailableVpnStateDoesNotFloodEventHistory() {
        service.vpnFallback=true; service.vpnUnknown=true;
        machine.configure(backgroundConfig(true,APPS,3));
        long errors=logs.stream().filter(s -> s.contains("无法确认系统 VPN 状态")).count();
        for(int i=0;i<20;i++)machine.applicationsChanged();
        assertEquals(errors,logs.stream().filter(s -> s.contains("无法确认系统 VPN 状态")).count());
        assertEquals("ERROR",status.code()); assertEquals(0,service.starts);
    }
    private void backgroundEnter(){
        service.activeApps=Set.of("a");machine.configure(backgroundConfig(true,APPS,3));enter();
    }
    @Test public void returningHomeRetainsProxyWhileSelectedAppRuns(){
        backgroundEnter();machine.foreground("home",0);clock.advance(60000);
        assertEquals(0,service.stops);assertTrue(status.owned());assertEquals("OWNED",status.code());
    }
    @Test public void lastSelectedAppExitStartsDelayAndClosesOwned(){
        backgroundEnter();machine.foreground("home",0);service.activeApps=Set.of();machine.applicationsChanged();
        assertEquals("WAIT_STOP",status.code());clock.advance(2999);assertEquals(0,service.stops);
        clock.advance(1);assertEquals(1,service.stops);clock.advance(300);assertFalse(status.owned());assertFalse(status.running());
    }
    @Test public void anotherSelectedBackgroundAppRetainsProxy(){
        backgroundEnter();machine.foreground("home",0);service.activeApps=Set.of("b");machine.applicationsChanged();clock.advance(60000);
        assertEquals(0,service.stops);assertEquals(Set.of("b"),machine.runningApps());
    }
    @Test public void reopeningAnySelectedAppCancelsLastExitDelay(){
        backgroundEnter();machine.foreground("home",0);service.activeApps=Set.of();machine.applicationsChanged();clock.advance(2000);
        service.activeApps=Set.of("b");machine.foreground("b",0);clock.advance(60000);assertEquals(0,service.stops);
    }
    @Test public void finalRecheckPreventsClosingForNewlyRunningBackgroundApp(){
        backgroundEnter();machine.foreground("home",0);service.activeApps=Set.of();machine.applicationsChanged();clock.advance(2000);
        service.activeApps=Set.of("b");clock.advance(1000);assertEquals(0,service.stops);assertEquals("OWNED",status.code());
    }
    @Test public void backgroundEventsAloneNeverStartAProxy(){
        service.activeApps=Set.of("a");machine.configure(backgroundConfig(true,APPS,3));machine.foreground("home",0);
        machine.applicationsChanged();clock.advance(60000);assertEquals(0,service.starts);
    }
    @Test public void selectedProcessDeathClearsStaleForegroundAndDoesNotRestart(){
        backgroundEnter();service.activeApps=Set.of();machine.applicationsChanged();clock.advance(20000);
        assertEquals(1,service.starts);assertEquals(1,service.stops);assertFalse(status.owned());
        service.activeApps=Set.of("a");machine.applicationsChanged();clock.advance(5000);assertEquals(1,service.starts);
    }
    @Test public void manualProxySurvivesAllSelectedAppsExiting(){
        service.manual(VPN);service.activeApps=Set.of("a");machine.configure(backgroundConfig(true,APPS,3));enter();
        service.activeApps=Set.of();machine.foreground("home",0);machine.applicationsChanged();clock.advance(60000);
        assertEquals(0,service.starts);assertEquals(0,service.stops);assertFalse(status.owned());
    }
    @Test public void externallyReplacedProxySurvivesCleanup(){
        backgroundEnter();service.manual(VPN);machine.serviceChanged();service.activeApps=Set.of();machine.foreground("home",0);
        clock.advance(60000);assertEquals(0,service.stops);assertFalse(status.owned());
    }
    @Test public void screenOffKeepsBackgroundProxyByDefault(){
        backgroundEnter();machine.foreground("home",0);machine.screen(false);clock.advance(60000);assertEquals(0,service.stops);
    }
    @Test public void optionalScreenOffRuleStillClosesOnlyOwned(){
        backgroundEnter();machine.configure(new ProxyMachine.Config(true,APPS,VPN,3,true,true,true,true,true));machine.screen(false);
        assertEquals(1,service.stops);
    }
    @Test public void unknownPresenceDoesNotCloseOwnedProxy(){
        backgroundEnter();machine.foreground("home",0);service.presenceFail=true;machine.applicationsChanged();clock.advance(60000);
        assertEquals(0,service.stops);assertTrue(status.owned());assertFalse(machine.presenceKnown());assertEquals("ERROR",status.code());
    }
    @Test public void presenceRecoversAndThenClosesAfterLastExit(){
        backgroundEnter();machine.foreground("home",0);service.presenceFail=true;machine.applicationsChanged();
        service.presenceFail=false;service.activeApps=Set.of();machine.applicationsChanged();clock.advance(3300);assertFalse(status.owned());
    }
    @Test public void removingAllRelevantAppsFromSelectionReleasesOwned(){
        backgroundEnter();machine.configure(backgroundConfig(true,Set.of("b"),3));clock.advance(3300);assertEquals(1,service.stops);
    }
    @Test public void disablingAutomationReleasesOwnedEvenWithBackgroundApps(){
        backgroundEnter();machine.configure(backgroundConfig(false,APPS,3));clock.advance(300);assertEquals(1,service.stops);assertFalse(status.owned());
    }
    @Test public void shutdownRequiresVpnTunnelToDisappearAsWellAsService(){
        backgroundEnter();service.vpnAlive=true;service.keepTunnel=true;machine.foreground("home",0);service.activeApps=Set.of();machine.applicationsChanged();
        clock.advance(3300);assertEquals("STOPPING",status.code());assertTrue(status.owned());assertTrue(status.running());
        service.vpnAlive=false;clock.advance(500);assertFalse(status.owned());assertFalse(status.running());
    }
    @Test public void lingeringVpnIsNotFalselyReportedClosed(){
        backgroundEnter();service.vpnAlive=true;service.keepTunnel=true;machine.foreground("home",0);service.activeApps=Set.of();machine.applicationsChanged();
        clock.advance(15000);assertEquals("ERROR",status.code());assertTrue(status.owned());assertTrue(status.running());assertEquals(1,service.stops);
    }
    @Test public void preexistingVpnWithoutStartedServiceIsManualAndProtected(){
        service.vpnAlive=true;service.activeApps=Set.of("a");machine.configure(backgroundConfig(true,APPS,3));enter();
        machine.foreground("home",0);service.activeApps=Set.of();machine.applicationsChanged();clock.advance(60000);
        assertEquals(0,service.starts);assertEquals(0,service.stops);assertFalse(status.owned());
    }
    @Test public void ownershipRecheckCanCancelCloseWithoutTouchingReplacement(){
        backgroundEnter();service.rejectOwnership=true;service.activeApps=Set.of();machine.foreground("home",0);clock.advance(5000);
        assertEquals(0,service.stops);assertFalse(status.owned());assertEquals("MANUAL",status.code());
    }
    @Test public void normalStopGetsAtMostOneBoundedFallback(){
        backgroundEnter();service.neverStops=true;service.activeApps=Set.of();machine.foreground("home",0);clock.advance(60000);
        assertEquals(1,service.stops);assertEquals(1,service.fallbackStops);assertEquals("ERROR",status.code());
    }
    @Test public void backgroundRuleNeverClosesManualEvenIfOldProtectionPreferenceWasFalse(){
        service.manual(VPN);service.activeApps=Set.of();machine.configure(new ProxyMachine.Config(true,APPS,VPN,0,false,false,true,true,true));
        machine.foreground("home",0);machine.applicationsChanged();clock.advance(60000);assertEquals(0,service.stops);
    }
    @Test public void backgroundScreenOffNeverClosesManualWithOldProtectionPreferenceFalse(){
        service.manual(VPN);service.activeApps=Set.of("a");machine.configure(new ProxyMachine.Config(true,APPS,VPN,0,false,true,true,true,true));
        machine.foreground("a",0);machine.screen(false);clock.advance(60000);assertEquals(0,service.stops);
    }
}
