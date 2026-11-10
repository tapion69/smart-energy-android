package fr.smartenergy.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read local ESP32-S3 Voltronic bridge only. No POST/PUT/parameter writes. */
object VoltronicReadOnlyClient {
    fun probe(host:String, accessCode:String, port:Int=18081):JSONObject {
        require(SunSpecReadOnly.isPrivateIPv4(host)) {
            "Adresse LAN privée obligatoire pour le lecteur Voltronic"
        }
        require(accessCode.length in 8..32)
        require(port in 1..65535)
        val connection=(URL("http://$host:$port/api/v1/info")
            .openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod="GET"
            connection.connectTimeout=2500
            connection.readTimeout=3500
            connection.setRequestProperty("X-Setup-Code",accessCode)
            if(connection.responseCode!=200)throw IllegalArgumentException(
                "Réponse du lecteur Voltronic : HTTP " + connection.responseCode)
            val body=connection.inputStream.bufferedReader().use { it.readText() }
            val info=JSONObject(body)
            require(info.optString("source")=="voltronic-rs232-read-only" &&
                info.optBoolean("read_only")) { "Ce n'est pas un lecteur Voltronic compatible" }
            info
        } finally { connection.disconnect() }
    }
}
