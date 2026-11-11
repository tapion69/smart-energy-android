package fr.smartenergy.android

/**
 * Pure, testable transport selection. In automatic mode, Tailscale presence
 * selects the secure VPN gateway; when it is absent, a previously paired
 * local reader is restored without user interaction. No LAN fallback is
 * permitted when a VPN is active (except explicit manual-local mode).
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
        // Automatic mode follows Tailscale, not the last manually used route.
        // VPN up -> use the authenticated S3 gateway; VPN down -> use the
        // previously paired local JK reader without requiring a new tap.
        // Never fall back to local silently while *any* VPN is active.
        if (vpnAutoEnabled) {
            return if (vpnActive) {
                if (gatewayReady) Route.GATEWAY_VPN else Route.CONFIGURE_VPN
            } else {
                if (localReady) Route.LOCAL_WIFI else Route.NO_CONNECTION
            }
        }
        // With automatic mode disabled, preserve explicit user choices.
        if (preference == "local")
            return if (localReady) Route.LOCAL_WIFI else Route.NO_CONNECTION
        if (vpnActive || preference == "gateway")
            return if (vpnActive && gatewayReady && preference == "gateway")
                Route.GATEWAY_VPN else Route.CONFIGURE_VPN
        return if (localReady) Route.LOCAL_WIFI else Route.NO_CONNECTION
    }
}
