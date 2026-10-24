package io.github.clashautoproxy.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class VpnSafetyPolicyTest {
    @Test public void noConfigMeansNoVpn(){ assertEquals(VpnSafetyPolicy.Owner.NONE,VpnSafetyPolicy.owner("clash",false,"")); }
    @Test public void targetPackageIsNeverClassifiedAsAnotherVpn(){ assertEquals(VpnSafetyPolicy.Owner.TARGET,VpnSafetyPolicy.owner("clash",true,"clash")); }
    @Test public void differentPackageIsProtected(){ assertEquals(VpnSafetyPolicy.Owner.OTHER,VpnSafetyPolicy.owner("clash",true,"other")); }
    @Test public void unknownOwnerIsDistinctFromAnotherVpn(){ assertEquals(VpnSafetyPolicy.Owner.UNKNOWN,VpnSafetyPolicy.owner("clash",true,null)); assertEquals(VpnSafetyPolicy.Owner.UNKNOWN,VpnSafetyPolicy.owner("clash",true,"")); }
}
