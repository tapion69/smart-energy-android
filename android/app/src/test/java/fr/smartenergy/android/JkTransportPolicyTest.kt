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
}
