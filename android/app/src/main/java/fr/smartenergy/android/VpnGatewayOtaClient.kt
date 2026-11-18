package fr.smartenergy.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update the gateway itself, not a relayed inverter/BMS.
 * The ESP32-S3 fetches its approved GitHub firmware using HOME Wi-Fi;
 * the phone sends only an explicitly confirmed tiny request over Tailscale.
 * Classic ESP32 4MB / legacy single-app S3 always fail preflight.
 */
internal object VpnGatewayOtaClient {
    private fun connection(route:VoltronicVpnRoute,path:String):HttpURLConnection {
        require(VoltronicVpnReadOnlyClient.isTailnetIpv4(route.gatewayIp)) {
            "Adresse VPN Tailscale invalide"
        }
        require(route.gatewayToken.length in 20..128) {"Token de passerelle requis"}
        require(path in setOf("/api/v1/vpn/ota/status","/api/v1/vpn/ota/request"))
        return route.vpnNetwork.openConnection(
            URL("http://${route.gatewayIp}:18767$path")
        ).let{it as HttpURLConnection}.apply {
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=5000
            readTimeout=12000
            setRequestProperty("Connection","close")
            setRequestProperty("X-Gateway-Token",route.gatewayToken)
        }
    }
    fun status(route:VoltronicVpnRoute):JSONObject {
        val c=connection(route,"/api/v1/vpn/ota/status")
        return try {
            c.requestMethod="GET"
            require(c.responseCode==200) {
                when(c.responseCode) {
                    404->"Ancien firmware VPN : service OTA absent"
                    401->"Token de passerelle incorrect"
                    403->"L'accès doit passer par le VPN Tailscale"
                    else->"Erreur VPN HTTP ${c.responseCode}"
                }
            }
            val body=c.inputStream.bufferedReader().use {it.readText().take(4097)}
            require(body.length in 2..4096){"Diagnostic OTA VPN invalide"}
            JSONObject(body).also {
                require(it.optString("product")=="smart-energy-vpn-gateway" &&
                        it.optString("source")=="smart-energy-vpn-ota-status"){
                    "Le firmware n'est pas notre passerelle VPN"
                }
            }
        }finally{c.disconnect()}
    }
    fun requestConfirmed(route:VoltronicVpnRoute):JSONObject {
        val ready=status(route)
        require(ready.optString("target")=="esp32s3-n16r8" &&
            ready.optBoolean("ota_supported") &&
            ready.optBoolean("flash_ready") &&
            ready.optString("partition_scheme")=="dual_ota" &&
            !ready.optBoolean("rollback_pending")) {
            "Migration USB ou récupération nécessaire : OTA VPN non autorisée"
        }
        val c=connection(route,"/api/v1/vpn/ota/request")
        return try {
            c.requestMethod="POST"
            c.doOutput=true
            c.setRequestProperty("X-OTA-Confirmed","yes")
            c.setRequestProperty("Content-Type","application/json")
            val body=JSONObject().put("confirmed",true)
                .put("action","check_and_install").toString().toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use{it.write(body)}
            require(c.responseCode==200) {
                "La passerelle VPN refuse l'OTA (HTTP ${c.responseCode})"
            }
            val answer=JSONObject(c.inputStream.bufferedReader().use {
                it.readText().take(2048)
            })
            require(answer.optString("product")=="smart-energy-vpn-gateway" &&
                answer.optBoolean("accepted") &&
                answer.optString("operation")=="check_and_install") {
                "Confirmation OTA non reçue : ne recommence pas automatiquement"
            }
            answer
        }finally{c.disconnect()}
    }
    fun verifyAfterReboot(route:VoltronicVpnRoute,version:String):Boolean {
        repeat(18) {
            Thread.sleep(2500)
            try {
                val state=status(route)
                if(SmartEnergyUpdateFeed.versionParts(state.optString("version",""))==
                    SmartEnergyUpdateFeed.versionParts(version) &&
                   !state.optBoolean("rollback_pending") &&
                   !state.optBoolean("in_progress"))return true
            }catch(_:Exception) {
                // TailTiny restarts: no retries of the OTA command, only GET.
            }
        }
        return false
    }
}
