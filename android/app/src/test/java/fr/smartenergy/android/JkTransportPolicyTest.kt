package fr.smartenergy.android

import fr.smartenergy.android.JkTransportPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class JkTransportPolicyTest {
    @Test fun noSilentLocalFallbackWhenVpnActiveAndTokenMissing() {
        assertEquals(Route.CONFIGURE_VPN,
            JkTransportPolicy.select(true,"auto",true,false,true))
    }
    @Test fun vpnDetectedAndAutoConfiguredTakesPrecedenceOverOldLan() {
        assertEquals(Route.GATEWAY_VPN,
            JkTransportPolicy.select(true,"auto",true,true,true))
    }
    @Test fun vpnSelectedAndConfiguredTakesPrecedenceEvenWithoutAuto() {
        assertEquals(Route.GATEWAY_VPN,
            JkTransportPolicy.select(true,"gateway",false,true,true))
    }
    @Test fun autoReturnsToLocalWhenTailscaleTurnsOffEvenAfterManualGatewaySelection() {
        assertEquals(Route.LOCAL_WIFI,
            JkTransportPolicy.select(false,"gateway",true,true,true))
    }
    @Test fun autoDoesNotTryLanWhenTailscaleActiveButGatewayUnavailable() {
        assertEquals(Route.CONFIGURE_VPN,
            JkTransportPolicy.select(true,"local",true,false,true))
    }
    @Test fun autoWithoutWifiOrPairedReaderDoesNotClaimLocalConnection() {
        assertEquals(Route.NO_CONNECTION,
            JkTransportPolicy.select(false,"gateway",true,true,false))
    }
    @Test fun explicitManualGatewayWithoutAutoStillRequiresVpn() {
        assertEquals(Route.CONFIGURE_VPN,
            JkTransportPolicy.select(false,"gateway",false,true,true))
    }
    @Test fun manualLocalWithAutoDisabledAllowsJKSettings() {
        assertEquals(Route.LOCAL_WIFI,
            JkTransportPolicy.select(true,"local",false,true,true))
    }
    @Test fun manualLocalIsStillPossibleWithVpnActive() {
        assertEquals(Route.LOCAL_WIFI,
            JkTransportPolicy.select(true,"local",false,true,true))
    }
    @Test fun vpnAutoOverridesOldLocalChoiceWhenActivated() {
        assertEquals(Route.GATEWAY_VPN,
            JkTransportPolicy.select(true,"local",true,true,true))
    }
    @Test fun normalHomeUseWithoutVpnStillUsesLan() {
        assertEquals(Route.LOCAL_WIFI,
            JkTransportPolicy.select(false,"auto",true,true,true))
    }
    @Test fun unpairedDeviceHasNoRoute() {
        assertEquals(Route.NO_CONNECTION,
            JkTransportPolicy.select(false,"auto",false,false,false))
    }

    @Test fun remoteParameterRoutesRemainStrictlyAllowlisted() {
        assertEquals("/api/v1/jk/1/info",
            JkTransportPolicy.vpnParameterPath(1,"GET","/api/v1/info"))
        assertEquals("/api/v1/jk/2/write-arm",
            JkTransportPolicy.vpnParameterPath(2,"POST","/api/v1/jk/write-arm"))
        assertEquals("/api/v1/jk/3/write",
            JkTransportPolicy.vpnParameterPath(3,"POST","/api/v1/jk/write"))
        assertEquals("/api/v1/jk/1/settings-refresh",
            JkTransportPolicy.vpnParameterPath(1,"POST","/api/v1/jk/settings-refresh"))
        assertEquals("/api/v1/jk/2/write-status?mac=AA%3ABB%3ACC%3ADD%3AEE%3AFF",
            JkTransportPolicy.vpnParameterPath(2,"GET",
                "/api/v1/jk/write-status?mac=AA%3ABB%3ACC%3ADD%3AEE%3AFF"))
        for(path in listOf("/api/v1/wifi","/api/v1/access",
                "/api/v1/bms","/api/v1/jk/bms/scan",
                "/api/v1/ota/image","/api/v1/jk/write?host=evil"))
            org.junit.Assert.assertNull(JkTransportPolicy.vpnParameterPath(1,"POST",path))
        org.junit.Assert.assertNull(
            JkTransportPolicy.vpnParameterPath(1,"GET",
                "/api/v1/jk/write-status?mac=AA%3ABB%3ACC%3ADD%3AEE%3AGG"))
        org.junit.Assert.assertNull(
            JkTransportPolicy.vpnParameterPath(4,"POST","/api/v1/jk/write"))
    }
    @org.junit.Test fun vpnReaderSlotsKeepLegacyAndRejectUnknownRoutes() {
        org.junit.Assert.assertEquals("/api/v1/jk/telemetry",
            JkTransportPolicy.vpnReadPath(1,"/api/v1/telemetry"))
        org.junit.Assert.assertEquals("/api/v1/jk/2/telemetry",
            JkTransportPolicy.vpnReadPath(2,"/api/v1/telemetry"))
        org.junit.Assert.assertEquals("/api/v1/jk/3/details",
            JkTransportPolicy.vpnReadPath(3,"/api/v1/details"))
        org.junit.Assert.assertEquals("/api/v1/health",
            JkTransportPolicy.vpnReadPath(3,"/api/v1/health"))
        org.junit.Assert.assertNull(JkTransportPolicy.vpnReadPath(4,"/api/v1/telemetry"))
        org.junit.Assert.assertNull(JkTransportPolicy.vpnReadPath(2,"/api/v1/jk/write"))
        org.junit.Assert.assertNull(JkTransportPolicy.vpnReadPath(2,"/api/v1/wifi"))
    }
}
