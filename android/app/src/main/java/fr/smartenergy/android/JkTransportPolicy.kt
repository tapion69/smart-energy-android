package fr.smartenergy.android

/**
 * Pure, testable transport selection. A detected VPN must never trigger a
 * silent LAN fallback. An explicit local choice is the only exception.
 */
object JkTransportPolicy {
    enum class Route { GATEWAY_VPN, LOCAL_WIFI, CONFIGURE_VPN, NO_CONNECTION }

    // The first reader retains legacy VPN paths for deployed gateways.
    // Readers 2/3 require the 0.2.0-dev-multi3 private gateway.
    // Never transform arbitrary HTTP paths or allow writes.
    fun vpnReadPath(reader:Int,path:String):String? {
        if(reader !in 1..3) return null
        return when(path) {
            "/api/v1/health" -> path
            "/api/v1/telemetry" -> if(reader==1) "/api/v1/jk/telemetry"
                else "/api/v1/jk/$reader/telemetry"
            "/api/v1/details" -> if(reader==1) "/api/v1/jk/details"
                else "/api/v1/jk/$reader/details"
            else -> null
        }
    }

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
