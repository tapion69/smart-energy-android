package fr.smartenergy.android

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * First-device experimental OTA, entirely inside the APK. The bundled 0.3.4
 * image is pinned to the exact SHA256 produced by CI run 43585410799.
 * Not a generic file picker, arbitrary URL or unrestricted firmware flasher.
 * No changes to JK, VPN or Android configuration.
 */
internal object VoltronicBundledOta {
    private const val TEST_IMAGE = "voltronic-s3-0.3.4-test.bin"
    private const val EXPECTED_BYTES = 901664L
    private const val EXPECTED_SHA256 =
        "9cfc8c87280c50bf92eb4f6b8029ea67004aa79cfbe7ff0faa0f24f78e3bf289"
    private const val FROM_VERSION = "0.3.2-dev-ota-gated-read-only"
    const val TO_VERSION = "0.3.4-dev-ota-parameter-edit-test"

    data class Preflight(val installedVersion:String,val maxImageBytes:Int)

    private fun connection(
        host:String,port:Int,code:String,network:Network,path:String
    ):HttpURLConnection {
        require(SunSpecReadOnly.isPrivateIPv4(host)) { "Adresse locale ESP32 invalide" }
        require(port in 1..65535) { "Port ESP32 invalide" }
        require(code.length in 8..32) { "Code ESP32 invalide" }
        return (network.openConnection(URL("http://$host:$port/api/v1/$path"))
            as HttpURLConnection).apply {
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=5000
            readTimeout=10000
            setRequestProperty("X-Setup-Code",code)
            setRequestProperty("Connection","close")
        }
    }

    private fun info(host:String,port:Int,code:String,network:Network):JSONObject {
        val c=connection(host,port,code,network,"info")
        return try {
            c.requestMethod="GET"
            require(c.responseCode==200) { "Lecture de l'ESP32 refusée : HTTP ${c.responseCode}" }
            val data=c.inputStream.bufferedReader().use{ it.readText().take(4096) }
            JSONObject(data)
        } finally { c.disconnect() }
    }

    private fun verifyBundledImage(context:Context):Unit {
        val digest=MessageDigest.getInstance("SHA-256")
        var bytes=0L
        var head=ByteArray(14)
        var first=0
        context.assets.open(TEST_IMAGE).use { input ->
            val buffer=ByteArray(8192)
            while(true) {
                val n=input.read(buffer)
                if(n<0)break
                if(first<14) {
                    val copy=minOf(n,14-first)
                    System.arraycopy(buffer,0,head,first,copy)
                    first+=copy
                }
                bytes+=n
                require(bytes<=EXPECTED_BYTES) { "Image OTA plus grande que prévu" }
                digest.update(buffer,0,n)
            }
        }
        require(first==14 && (head[0].toInt() and 255)==0xe9 &&
            (head[12].toInt() and 255)==9 && (head[13].toInt() and 255)==0) {
            "Le fichier n'est pas une image ESP32-S3 attendue"
        }
        val sha=digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(bytes==EXPECTED_BYTES && sha==EXPECTED_SHA256) {
            "Image 0.3.4 absente ou différente de la compilation vérifiée"
        }
    }

    /** Read-only. Fail closed unless this is the exact tested upgrade path. */
    fun preflight(
        context:Context,host:String,code:String,port:Int,network:Network
    ):Preflight {
        val device=info(host,port,code,network)
        require(device.optString("product")=="smart-energy-voltronic-s3" &&
            device.optString("chip")=="esp32s3") {
            "Le lecteur n'est pas notre ESP32-S3 Voltronic"
        }
        val version=device.optString("version","")
        require(version==FROM_VERSION) {
            "OTA test réservé à $FROM_VERSION ; version actuelle : $version"
        }
        val status=VoltronicLocalOta.status(host,code,port,network)
        require(status.optBoolean("ota_supported",false) &&
            status.optString("partition_scheme")=="dual_ota") {
            "Double partition OTA absente : aucune écriture"
        }
        require(status.optString("target")=="esp32s3-n16r8") {
            "Cible matérielle incorrecte"
        }
        require(!status.optBoolean("rollback_pending",false)) {
            "Rollback OTA en attente : mise à jour refusée"
        }
        if(status.has("flash_ready")) require(status.optBoolean("flash_ready")) {
            "Firmware non prêt à recevoir l'OTA"
        }
        val available=status.optInt("max_image_bytes",0)
        require(available>=EXPECTED_BYTES && available<=8388608) {
            "Partition OTA trop petite ou invalide"
        }
        verifyBundledImage(context)
        return Preflight(version,available)
    }

    /**
     * One user-confirmed request, no automatic retries. ESP-IDF checks image
     * and the ESP also hashes streamed bytes before switching boot slot.
     */
    fun install(
        context:Context,host:String,code:String,port:Int,network:Network,
        progress:(String)->Unit
    ):JSONObject {
        preflight(context,host,code,port,network)
        progress("Image 0.3.4 vérifiée · envoi OTA local en cours…")
        val c=connection(host,port,code,network,"ota/image")
        try {
            c.requestMethod="POST"
            c.doOutput=true
            c.connectTimeout=7000
            c.readTimeout=45000
            c.setFixedLengthStreamingMode(EXPECTED_BYTES)
            c.setRequestProperty("Content-Type","application/octet-stream")
            c.setRequestProperty("X-OTA-Confirmed","yes")
            c.setRequestProperty("X-Image-SHA256",EXPECTED_SHA256)
            c.outputStream.use { out ->
                context.assets.open(TEST_IMAGE).use { input ->
                    val buffer=ByteArray(4096)
                    var sent=0L
                    while(true) {
                        val n=input.read(buffer)
                        if(n<0)break
                        sent+=n
                        require(sent<=EXPECTED_BYTES) { "Taille image incorrecte" }
                        out.write(buffer,0,n)
                    }
                    require(sent==EXPECTED_BYTES) { "Image OTA tronquée" }
                }
            }
            val status=c.responseCode
            require(status==200) { "ESP32 a refusé l'OTA : HTTP $status" }
            val response=JSONObject(c.inputStream.bufferedReader().use {
                it.readText().take(2048)
            })
            require(response.optBoolean("accepted") && response.optBoolean("rebooting")) {
                "Réponse OTA non confirmée — ne pas réessayer automatiquement"
            }
            progress("OTA acceptée · attente du redémarrage du S3…")
            return response
        } finally { c.disconnect() }
    }

    /** Device must report its new version; do not interpret HTTP ACK as full success. */
    fun verifyAfterReboot(host:String,code:String,port:Int,network:Network):Boolean {
        repeat(12) {
            Thread.sleep(2500)
            try {
                val version=info(host,port,code,network).optString("version","")
                if(version==TO_VERSION)return true
                if(version.isNotEmpty() && version!=FROM_VERSION)return false
            }catch(_:Exception) {
                // Temporary Wi-Fi outage while the ESP32-S3 reboots.
            }
        }
        return false
    }
}
