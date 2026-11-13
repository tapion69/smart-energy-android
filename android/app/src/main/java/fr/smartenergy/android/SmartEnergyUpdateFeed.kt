package fr.smartenergy.android

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only signed-release discovery. A manifest never executes installers or
 * OTA: it only tells the user whether an official GitHub download is available.
 */
internal object SmartEnergyUpdateFeed {
    const val ANDROID_MANIFEST =
        "https://raw.githubusercontent.com/tapion69/smart-energy-android/main/updates/android.json"
    const val VOLTRONIC_MANIFEST =
        "https://raw.githubusercontent.com/tapion69/smart-energy-android/main/updates/voltronic-esp32.json"

    data class AndroidRelease(
        val version:String,val versionCode:Long,val downloadUrl:String,
        val sha256:String,val releaseNotesUrl:String
    )
    data class FirmwareRelease(
        val version:String,val downloadUrl:String,val sha256:String,val compatible:Boolean
    )
    fun officialApkUrl(url:String):Boolean = try {
        val u=URL(url)
        u.protocol=="https" &&
            u.host=="github.com" &&
            u.port==-1 &&
            u.query==null &&
            u.ref==null &&
            u.path.startsWith(
                "/tapion69/smart-energy-android/releases/download/") &&
            u.path.endsWith(".apk") &&
            !u.path.contains("..") &&
            u.path.length<512
    }catch(_:Exception){false}
    fun officialFirmwareUrl(url:String):Boolean=try {
        val u=URL(url)
        u.protocol=="https" && u.host=="github.com" &&
            u.port==-1 && u.query==null && u.ref==null &&
            u.path.startsWith("/tapion69/smart-energy-android/releases/download/") &&
            u.path.endsWith(".bin") &&
            !u.path.contains("..") && u.path.length<512
    }catch(_:Exception){false}
    fun parseAndroid(text:String,currentCode:Long):AndroidRelease? {
        val json=JSONObject(text)
        if(json.optInt("schemaVersion",0)!=1 ||
            json.optString("product")!="smart-energy-android" ||
            !json.optBoolean("available",false))return null
        val code=json.optLong("versionCode",-1L)
        if(code<=currentCode)return null
        val minimum=json.optLong("minimumSupportedVersionCode",0L)
        if(minimum>0L && currentCode<minimum)return null
        val version=json.optString("versionName","").take(64)
        val url=json.optString("downloadUrl","")
        val hash=json.optString("sha256","")
        val notes=json.optString("releaseNotesUrl","")
        if(!version.matches(Regex("[A-Za-z0-9._-]{1,64}")) ||
            !officialApkUrl(url) ||
            !hash.matches(Regex("[a-fA-F0-9]{64}")))return null
        val approvedNotes=if(notes.startsWith(
            "https://github.com/tapion69/smart-energy-android/releases/"))notes else ""
        return AndroidRelease(version,code,url,hash.lowercase(),approvedNotes)
    }
    fun parseVoltronic(text:String,currentVersion:String):FirmwareRelease? {
        val json=JSONObject(text)
        if(json.optInt("schemaVersion",0)!=1 ||
            json.optString("product")!="smart-voltronic-esp32" ||
            !json.optBoolean("available",false))return null
        val name=json.optString("version","")
        if(name.isBlank() || name==currentVersion)return null
        val url=json.optString("downloadUrl","")
        val hash=json.optString("sha256","")
        val hardware=json.optJSONArray("hardwareTargets")
        val compatible=(0 until (hardware?.length()?:0)).any {
            hardware?.optString(it)=="esp32s3-n16r8"
        }
        if(!officialFirmwareUrl(url) ||
            !hash.matches(Regex("[a-fA-F0-9]{64}")))return null
        return FirmwareRelease(name,url,hash.lowercase(),compatible)
    }
    /** Fail closed: bounded HTTPS, no redirects to arbitrary providers. */
    fun fetchPublicManifest(url:String):String {
        require(url==ANDROID_MANIFEST || url==VOLTRONIC_MANIFEST)
        val conn=URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod="GET"
            conn.instanceFollowRedirects=false
            conn.connectTimeout=6000
            conn.readTimeout=6500
            conn.setRequestProperty("Accept","application/json")
            conn.setRequestProperty("Cache-Control","no-cache")
            require(conn.responseCode==200){"GitHub HTTP "+conn.responseCode}
            val bytes=conn.inputStream.use {input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(2048)
                while(true){
                    val n=input.read(buffer)
                    if(n<0)break
                    output.write(buffer,0,n)
                    require(output.size()<=8192){"Manifest too large"}
                }
                output.toByteArray()
            }
            String(bytes,Charsets.UTF_8)
        }finally{conn.disconnect()}
    }
}
