package fr.smartenergy.android

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Opt-in JK reader OTA over the household Wi-Fi only.
 * Legacy JK firmware (ota=false) is NOT upgraded over the network.
 * No app writes NVS, pairing secrets or BMS settings during an OTA.
 */
internal object JkLocalOta {
    data class Ready(val version:String,val maxImageBytes:Int)
    private fun connection(
        host:String,code:String,network:Network,path:String
    ):HttpURLConnection {
        require(SunSpecReadOnly.isPrivateIPv4(host)) {"Adresse JK non locale"}
        require(code.length in 8..32) {"Code JK manquant"}
        return (network.openConnection(URL("http://$host$path"))
            as HttpURLConnection).apply {
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=4000
            readTimeout=9000
            setRequestProperty("X-Setup-Code",code)
            setRequestProperty("Connection","close")
        }
    }

    fun status(host:String,code:String,network:Network):JSONObject {
        val c=connection(host,code,network,"/api/v1/ota/status")
        return try {
            c.requestMethod="GET"
            require(c.responseCode==200) {
                "Firmware JK sans service OTA : migration USB préalable requise"
            }
            val o=JSONObject(c.inputStream.bufferedReader().use{
                it.readText().take(4096)
            })
            require(o.optString("source")=="smart-jk-ota-status" &&
                o.optString("product")=="smart-jk-esp32" &&
                o.optString("target")=="esp32dev") {
                "Réponse OTA JK non reconnue"
            }
            o
        } finally {c.disconnect()}
    }

    private fun officialAssetTarget(url:URL):Boolean =
        url.protocol=="https" && url.port==-1 &&
        url.host in setOf("github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com")

    private fun download(
        context:Context,release:SmartEnergyUpdateFeed.FirmwareRelease,
        limit:Int,progress:(String)->Unit
    ):File {
        require(release.compatible &&
            SmartEnergyUpdateFeed.officialFirmwareUrl(release.downloadUrl)) {
            "Firmware JK non officiel"
        }
        val file=File.createTempFile("jk-ota-",".bin",context.cacheDir)
        try {
            progress("Téléchargement du firmware JK officiel…")
            val c=URL(release.downloadUrl).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects=true
                c.connectTimeout=12000;c.readTimeout=20000
                c.setRequestProperty("Accept","application/octet-stream")
                require(c.responseCode==200 && officialAssetTarget(c.url)) {
                    "Téléchargement GitHub invalide"
                }
                val md=MessageDigest.getInstance("SHA-256")
                var bytes=0
                c.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val chunk=ByteArray(4096)
                        while(true){
                            val n=input.read(chunk)
                            if(n<0)break
                            bytes+=n
                            require(bytes<=limit) {"Firmware plus grand que la partition OTA"}
                            md.update(chunk,0,n)
                            output.write(chunk,0,n)
                        }
                    }
                }
                require(bytes>=16384) {"Image JK incomplète"}
                val actual=md.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
                require(actual.equals(release.sha256,true)) {
                    "Empreinte SHA-256 différente, écriture interdite"
                }
                file.inputStream().use { stream ->
                    require(stream.read()==0xe9) {"Fichier ESP32 non reconnu"}
                    stream.skip(11)
                    require(stream.read()==0 && stream.read()==0) {
                        "Image non destinée à l'ESP32 classique"
                    }
                }
            }finally{c.disconnect()}
            return file
        }catch(ex:Exception){file.delete();throw ex}
    }

    fun install(
        context:Context,release:SmartEnergyUpdateFeed.FirmwareRelease,
        host:String,code:String,network:Network,
        progress:(String)->Unit
    ):JSONObject {
        val state=status(host,code,network)
        require(state.optBoolean("ota_supported") &&
            state.optBoolean("flash_ready") &&
            state.optString("partition_scheme")=="dual_ota" &&
            !state.optBoolean("rollback_pending")) {
            "ESP JK non prêt : partition OTA ou état de démarrage incompatible"
        }
        require(SmartEnergyUpdateFeed.newerFirmware(
            release.version,state.optString("version",""))) {
            "Ce firmware JK n'est pas plus récent"
        }
        val max=state.optInt("max_image_bytes",0)
        require(max in 262144..4194304) {"Taille de partition OTA JK invalide"}
        val file=download(context,release,max,progress)
        val marker="----SmartEnergyJkOtaBoundary"
        val header=("--$marker\r\n"+
            "Content-Disposition: form-data; name=\"firmware\"; filename=\"firmware.bin\"\r\n"+
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val end="\r\n--$marker--\r\n".toByteArray(Charsets.US_ASCII)
        try {
            progress("Image JK vérifiée · transfert local…")
            // Revalidate preflight immediately before touching the ESP32.
            val current=status(host,code,network)
            require(current.optBoolean("flash_ready") &&
                current.optString("version")==state.optString("version")) {
                "État du lecteur JK changé : OTA annulée"
            }
            val c=connection(host,code,network,"/api/v1/ota/image")
            try {
                c.requestMethod="POST"
                c.doOutput=true
                c.connectTimeout=7500
                c.readTimeout=45000
                c.setFixedLengthStreamingMode(header.size.toLong()+file.length()+end.size)
                c.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=$marker")
                c.setRequestProperty("X-OTA-Confirmed","yes")
                c.setRequestProperty("X-Image-SHA256",release.sha256)
                c.outputStream.use { out ->
                    out.write(header)
                    file.inputStream().use{it.copyTo(out,4096)}
                    out.write(end)
                }
                require(c.responseCode==200) {
                    "ESP JK a refusé l'OTA (HTTP ${c.responseCode})"
                }
                val result=JSONObject(c.inputStream.bufferedReader().use{
                    it.readText().take(2048)
                })
                require(result.optBoolean("accepted")&&result.optBoolean("rebooting")){
                    "L'ESP JK n'a pas confirmé l'OTA"
                }
                progress("OTA JK reçue · redémarrage…")
                return result
            }finally{c.disconnect()}
        }finally{file.delete()}
    }

    fun confirmVersion(
        host:String,code:String,network:Network,wanted:String
    ):Boolean {
        repeat(16){
            Thread.sleep(2000)
            try{
                val c=connection(host,code,network,"/api/v1/info")
                val reported=try{
                    require(c.responseCode==200)
                    JSONObject(c.inputStream.bufferedReader().use{
                        it.readText().take(4096)
                    }).optString("version","")
                }finally{c.disconnect()}
                if(reported==wanted)return true
            }catch(_:Exception){/* ESP restarts and reconnects */ }
        }
        return false
    }
}
