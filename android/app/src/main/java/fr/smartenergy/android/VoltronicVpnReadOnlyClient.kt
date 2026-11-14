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
    private fun connection(
        route:VoltronicVpnRoute,path:String
    ):HttpURLConnection {
        require(isTailnetIpv4(route.gatewayIp)) {"IP Tailscale requise"}
        require(route.gatewayToken.length in 20..128) {"Token VPN manquant"}
        return URL("http://${route.gatewayIp}:18767$path")
            .openConnection().let {it as HttpURLConnection}.apply {
                instanceFollowRedirects=false
                useCaches=false
                connectTimeout=3500
                readTimeout=6500
                setRequestProperty("X-Gateway-Token",route.gatewayToken)
                setRequestProperty("Connection","close")
            }
    }

    fun remoteWritesEnabled(route:VoltronicVpnRoute):Boolean {
        val c=connection(route,"/api/v1/health")
        return try {
            c.requestMethod="GET"
            require(c.responseCode==200){"Passerelle VPN inaccessible"}
            val result=JSONObject(c.inputStream.bufferedReader().use {
                it.readText().take(4096)
            })
            require(result.optString("product")=="smart-energy-vpn-gateway" &&
                result.optBoolean("vpn_only") && result.optBoolean("vpn_connected")) {
                "Identité VPN incorrecte"
            }
            result.optBoolean("remote_parameter_writes_opt_in",false)
        }finally{c.disconnect()}
    }

    fun info(slot:Int,route:VoltronicVpnRoute):JSONObject {
        require(slot in 1..3)
        val c=connection(route,"/api/v1/voltronic/$slot/info")
        return try {
            c.requestMethod="GET"
            val status=c.responseCode
            if(status!=200)throw VoltronicVpnHttpException(status)
            val result=JSONObject(c.inputStream.bufferedReader().use {
                it.readText().take(4096)
            })
            require(result.optString("source")=="voltronic-rs232-read-only" &&
                result.optString("chip")=="esp32s3" && result.optBoolean("read_only")) {
                "Lecteur Voltronic distant non reconnu"
            }
            result
        }finally{c.disconnect()}
    }

    /** Strictly semantic fixed write route, only after USB opt-in and
     * fresh QPIRI/QDOP readback. The gateway never forwards serial commands.
     * No retry after any failure: SET may have been applied already. */
    fun change(slot:Int,route:VoltronicVpnRoute,
               key:String,old:Double,new:Double):JSONObject {
        require(slot in 1..3)
        require(remoteWritesEnabled(route)){
            "Écritures VPN non autorisées sur l'ESP passerelle : "+
            "activer explicitement par USB après installation du nouveau firmware."
        }
        require(old.isFinite() && new.isFinite() && old!=new) {
            "Ancienne ou nouvelle valeur invalide"
        }
        val body=JSONObject().put("parameter",key).put("old",old)
            .put("value",new).put("confirmed",true).toString()
        require(body.toByteArray().size in 2..768)
        val c=connection(route,"/api/v1/voltronic/$slot/parameters/change")
        return try {
            c.requestMethod="POST"
            c.doOutput=true
            c.connectTimeout=6000
            c.readTimeout=25000
            c.setRequestProperty("Content-Type","application/json")
            c.setRequestProperty("X-Remote-Write-Confirmed","yes")
            val bytes=body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use {it.write(bytes)}
            require(c.responseCode==200) {
                "Écriture VPN Voltronic non confirmée (HTTP ${c.responseCode}). "+
                "Relire la valeur avant toute autre action."
            }
            val result=JSONObject(c.inputStream.bufferedReader().use{
                it.readText().take(2048)
            })
            require(result.optString("source")=="voltronic-parameter-verification") {
                "Réponse de modification Voltronic inattendue"
            }
            result
        }finally{c.disconnect()}
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
        // Match the working JK VPN transport: let Android/Tailscale route the
        // Tailnet address. Binding a socket to Android's VPN Network handle
        // fails with EPERM on some devices even though JK works normally.
        // The route is enabled only after a VPN Network is detected and
        // gatewayIp is restricted to 100.64.0.0/10.
        val c=url.openConnection() as HttpURLConnection
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
