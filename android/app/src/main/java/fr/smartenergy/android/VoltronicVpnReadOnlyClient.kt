package fr.smartenergy.android

import android.net.Network
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Safe status taxonomy: never expose the ESP IP, gateway token or secret. */
class VoltronicVpnHttpException(val statusCode:Int):java.io.IOException(
    "VPN Voltronic HTTP $statusCode"
) {
    val userHint:String get()=when(statusCode) {
        503 -> "INV non enregistré dans la passerelle VPN (503)"
        502 -> "INV enregistré mais ESP32-S3 Voltronic inaccessible (502)"
        401 -> "Token de passerelle VPN incorrect (401)"
        403 -> "Accès refusé : requête hors du VPN Tailscale (403)"
        404 -> "Route Voltronic absente : vérifier le firmware VPN (404)"
        else -> "Réponse du relais VPN HTTP $statusCode"
    }
}

/** Independent VPN route: fixed read-only Voltronic endpoints on the private
 * ESP32 TailTiny gateway. User credentials never go in query strings. */
data class VoltronicVpnRoute(
    val gatewayIp:String,
    val gatewayToken:String,
    val vpnNetwork:Network
)

object VoltronicVpnReadOnlyClient {
    fun isTailnetIpv4(ip:String):Boolean {
        val octets=ip.split(".")
        if(octets.size!=4)return false
        val a=octets.map { p ->
            p.toIntOrNull()?.takeIf {
                it in 0..255 && (p=="0" || !p.startsWith("0"))
            } ?: return false
        }
        return a[0]==100 && a[1] in 64..127
    }
    fun fixedPath(slot:Int,details:Boolean):String {
        require(slot in 1..3)
        return "/api/v1/voltronic/$slot/"+if(details)"details" else "telemetry"
    }
    fun read(slot:Int,details:Boolean,route:VoltronicVpnRoute):JSONObject {
        require(isTailnetIpv4(route.gatewayIp)){"Gateway must be Tailnet IPv4"}
        require(route.gatewayToken.length in 20..128){"Gateway token missing"}
        val path=fixedPath(slot,details)
        val url=URL("http://${route.gatewayIp}:18767$path")
        val c=route.vpnNetwork.openConnection(url) as HttpURLConnection
        return try {
            c.requestMethod="GET"
            c.instanceFollowRedirects=false
            c.useCaches=false
            c.setRequestProperty("Connection","close")
            c.setRequestProperty("X-Gateway-Token",route.gatewayToken)
            c.connectTimeout=3500
            c.readTimeout=5500
            val status=c.responseCode
            if(status!=200)throw VoltronicVpnHttpException(status)
            val input=c.inputStream.bufferedReader().use {it.readText()}
            require(input.length in 2..32768){"Invalid gateway response size"}
            val obj=JSONObject(input)
            val expected=if(details)"voltronic-details-read-only" else "voltronic-qpigs-read-only"
            require(obj.optString("source")==expected && obj.optBoolean("read_only")){
                "Invalid Voltronic gateway identity"
            }
            obj
        } finally {c.disconnect()}
    }
}
