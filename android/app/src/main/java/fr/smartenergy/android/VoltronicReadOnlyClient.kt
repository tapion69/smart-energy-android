package fr.smartenergy.android

import org.json.JSONObject
import android.net.Network
import java.net.HttpURLConnection
import java.net.URL

/** Read local ESP32-S3 Voltronic bridge only. No POST/PUT/parameter writes. */
object VoltronicReadOnlyClient {
    fun probe(host:String, accessCode:String, port:Int=18081, wifiNetwork:Network?=null):JSONObject {
        require(SunSpecReadOnly.isPrivateIPv4(host)) {
            "Adresse LAN privée obligatoire pour le lecteur Voltronic"
        }
        require(accessCode.length in 8..32)
        require(port in 1..65535)
        val url=URL("http://$host:$port/api/v1/info")
        val connection=(if(wifiNetwork!=null)wifiNetwork.openConnection(url)
            else url.openConnection()) as HttpURLConnection
        return try {
            connection.requestMethod="GET"
            connection.instanceFollowRedirects=false
            connection.useCaches=false
            connection.setRequestProperty("Connection","close")
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
    /** Optional solar day counter, fetched separately from live telemetry. */
    fun details(host:String,accessCode:String,port:Int=18081,wifiNetwork:Network?=null):JSONObject {
        require(SunSpecReadOnly.isPrivateIPv4(host))
        require(accessCode.length in 8..32)
        require(port in 1..65535)
        val url=URL("http://$host:$port/api/v1/details")
        val connection=(if(wifiNetwork!=null)wifiNetwork.openConnection(url)
            else url.openConnection()) as HttpURLConnection
        return try {
            connection.requestMethod="GET"
            connection.instanceFollowRedirects=false
            connection.useCaches=false
            connection.setRequestProperty("Connection","close")
            connection.connectTimeout=3000
            connection.readTimeout=4500
            connection.setRequestProperty("X-Setup-Code",accessCode)
            if(connection.responseCode!=200)
                throw IllegalArgumentException("Voltronic détails HTTP "+connection.responseCode)
            val body=connection.inputStream.bufferedReader().use {it.readText()}
            val obj=JSONObject(body)
            require(obj.optString("source")=="voltronic-details-read-only" &&
                obj.optBoolean("read_only")){"Réponse détails Voltronic invalide"}
            obj
        } finally {connection.disconnect()}
    }

    /** Live read-only snapshot; no serial commands exposed to the APK. */
    fun telemetry(host:String, accessCode:String, port:Int=18081, wifiNetwork:Network?=null):JSONObject {
        require(SunSpecReadOnly.isPrivateIPv4(host)) { "Adresse LAN privée obligatoire" }
        require(accessCode.length in 8..32)
        require(port in 1..65535)
        val url=URL("http://$host:$port/api/v1/telemetry")
        val connection=(if(wifiNetwork!=null)wifiNetwork.openConnection(url)
            else url.openConnection()) as HttpURLConnection
        return try {
            connection.requestMethod="GET"
            connection.instanceFollowRedirects=false
            connection.useCaches=false
            connection.setRequestProperty("Connection","close")
            connection.connectTimeout=3000
            connection.readTimeout=4500
            connection.setRequestProperty("X-Setup-Code",accessCode)
            if(connection.responseCode!=200)
                throw IllegalArgumentException("Lecteur Voltronic HTTP " + connection.responseCode)
            val body=connection.inputStream.bufferedReader().use { it.readText() }
            val snapshot=JSONObject(body)
            require(snapshot.optString("source")=="voltronic-qpigs-read-only" &&
                    snapshot.optBoolean("read_only")) {
                "Réponse Voltronic non reconnue"
            }
            snapshot
        } finally { connection.disconnect() }
    }
}
