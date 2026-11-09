package fr.smartenergy.android

/**
 * Pure, testable transport selection. A detected VPN must never trigger a
 * silent LAN fallback. An explicit local choice is the only exception.
 */
object JkTransportPolicy {
    enum class Route { GATEWAY_VPN, LOCAL_WIFI, CONFIGURE_VPN, NO_CONNECTION }

    fun select(
        vpnActive: Boolean,
        preference: String,
        vpnAutoEnabled: Boolean,
        gatewayReady: Boolean,
        localReady: Boolean
    ): Route {
        if (vpnActive && gatewayReady && vpnAutoEnabled) return Route.GATEWAY_VPN
        if (preference == "local") {
            return if (localReady) Route.LOCAL_WIFI else Route.NO_CONNECTION
        }
        if (vpnActive || preference == "gateway") {
            return if (vpnActive && gatewayReady && preference == "gateway")
                Route.GATEWAY_VPN else Route.CONFIGURE_VPN
        }
        return if (localReady) Route.LOCAL_WIFI else Route.NO_CONNECTION
    }
}
