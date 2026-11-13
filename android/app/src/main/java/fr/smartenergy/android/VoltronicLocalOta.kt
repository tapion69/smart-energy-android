package fr.smartenergy.android

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Local-only firmware OTA. APK downloads from the official GitHub release,
 * verifies SHA256, then uploads to the ESP32-S3 only when it reports an
 * available OTA partition. NEVER sends OTA through the VPN gateway.
 */
object VoltronicLocalOta {
    private fun otaConnection(host:String,code:String,port:Int,
                              network:Network,path:String):HttpURLConnection {
        require(SunSpecReadOnly.isPrivateIPv4(host))
        require(port in 1..65535)
        require(code.length in 8..32)
        return (network.openConnection(URL("http://$host:$port/api/v1/ota/$path"))
            as HttpURLConnection).apply {
            instanceFollowRedirects=false
            useCaches=false
            connectTimeout=3500
            readTimeout=7500
            setRequestProperty("X-Setup-Code",code)
            setRequestProperty("Connection","close")
        }
    }
    fun status(host:String,code:String,port:Int,network:Network):JSONObject {
        val c=otaConnection(host,code,port,network,"status")
        return try {
            c.requestMethod="GET"
            require(c.responseCode==200){"Le firmware actuel ne dispose pas de l'OTA"}
            val result=c.inputStream.bufferedReader().use {
                JSONObject(it.readText().take(4096))
            }
            require(result.optString("source")=="voltronic-ota-status" &&
                    result.optString("target")=="esp32s3-n16r8"){
                "ESP32-S3 OTA non reconnu"
            }
            result
        }finally{c.disconnect()}
    }
    private fun validGithubAssetTarget(url:URL):Boolean {
        return url.protocol=="https" && url.port==-1 &&
            (url.host=="github.com" ||
             url.host=="release-assets.githubusercontent.com" ||
             url.host=="objects.githubusercontent.com")
    }
    fun install(context:Context,release:SmartEnergyUpdateFeed.FirmwareRelease,
                host:String,code:String,port:Int,network:Network,
                onProgress:(String)->Unit):JSONObject {
        require(release.compatible && SmartEnergyUpdateFeed.officialFirmwareUrl(release.downloadUrl))
        require(SunSpecReadOnly.isPrivateIPv4(host))
        val status=status(host,code,port,network)
        require(status.optBoolean("ota_supported",false)){
            "Migration USB OTA nécessaire (partition unique actuelle)"
        }
        val limit=status.optInt("max_image_bytes",0)
        require(limit in 262144..8388608){"Partition OTA incompatible"}
        val temporary=File.createTempFile("s3-ota-",".bin",context.cacheDir)
        try {
            onProgress("Téléchargement GitHub…")
            val url=URL(release.downloadUrl)
            val c=url.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects=true
                c.connectTimeout=10000
                c.readTimeout=15000
                c.setRequestProperty("Accept","application/octet-stream")
                require(c.responseCode==200 && validGithubAssetTarget(c.url)){
                    "Le téléchargement ne provient pas de GitHub"
                }
                val md=MessageDigest.getInstance("SHA-256")
                var received=0
                c.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val chunk=ByteArray(4096)
                        while(true) {
                            val n=input.read(chunk)
                            if(n<0)break
                            received+=n
                            require(received<=limit){"Image trop volumineuse"}
                            md.update(chunk,0,n)
                            output.write(chunk,0,n)
                        }
                    }
                }
                require(received>=16384){"Image incomplète"}
                val digest=md.digest().joinToString("") {"%02x".format(it)}
                require(digest.equals(release.sha256,true)){
                    "Empreinte SHA-256 différente : installation refusée"
                }
            }finally{c.disconnect()}
            onProgress("Empreinte vérifiée · transfert local au S3…")
            val c=otaConnection(host,code,port,network,"image")
            try {
                c.requestMethod="POST"
                c.doOutput=true
                c.connectTimeout=7000
                c.readTimeout=30000
                c.setFixedLengthStreamingMode(temporary.length())
                c.setRequestProperty("Content-Type","application/octet-stream")
                c.setRequestProperty("X-Image-SHA256",release.sha256)
                c.setRequestProperty("X-OTA-Confirmed","yes")
                c.outputStream.use { out ->
                    temporary.inputStream().use { data->data.copyTo(out,4096) }
                }
                require(c.responseCode==200){
                    "Le firmware a refusé l'OTA (HTTP ${c.responseCode})"
                }
                val result=c.inputStream.bufferedReader().use {
                    JSONObject(it.readText().take(2048))
                }
                require(result.optBoolean("accepted") && result.optBoolean("rebooting")){
                    "Aucune confirmation OTA reçue"
                }
                onProgress("OTA acceptée · redémarrage du S3")
                return result
            }finally{c.disconnect()}
        }finally{temporary.delete()}
    }
}
