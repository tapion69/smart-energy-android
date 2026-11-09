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
    @Test fun keepSelectedVpnWithoutConnectionRatherThanFallBack() {
        assertEquals(Route.CONFIGURE_VPN,
            JkTransportPolicy.select(false,"gateway",true,true,true))
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
