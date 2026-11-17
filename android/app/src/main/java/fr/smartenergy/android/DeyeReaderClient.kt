package fr.smartenergy.android

import android.net.Network
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Reader Deye: ESP32 home Wi-Fi -> Solarman dongle. VPN only relays
 * authenticated fixed endpoints to the ESP32 identified by its name. */
object DeyeReaderClient {
    private val paths=setOf("info","telemetry","details","device-config",
        "ota/status","ota/request","parameters/change")
    private fun connection(slot:Int,path:String,method:String,host:String,
        code:String,vpn:VoltronicVpnRoute?,wifi:Network?):HttpURLConnection {
        require(slot in 1..3 && path in paths)
        require(method=="GET" || method=="POST")
        val url:URL
        if(vpn!=null){
            require(VoltronicVpnReadOnlyClient.isTailnetIpv4(vpn.gatewayIp))
            require(vpn.gatewayToken.length in 20..128)
            url=URL("http://"+vpn.gatewayIp+":18767/api/v1/deye/"+slot+"/"+path)
        }else{
            require(wifi!=null){"Wi-Fi local requis"}
            require(SunSpecReadOnly.isPrivateIPv4(host)){"Adresse privée de l'ESP32 requise"}
            require(code.length in 8..32){"Code ESP32 requis"}
            url=URL("http://"+host+":18081/api/v1/"+path)
        }
        val c=(if(vpn==null)wifi!!.openConnection(url) else url.openConnection())
            as HttpURLConnection
        c.requestMethod=method
        c.instanceFollowRedirects=false
        c.useCaches=false
        c.connectTimeout=5000
        c.readTimeout=9000
        c.setRequestProperty("Connection","close")
        if(vpn!=null)c.setRequestProperty("X-Gateway-Token",vpn.gatewayToken)
        else c.setRequestProperty("X-Setup-Code",code)
        return c
    }
    private fun parse(c:HttpURLConnection):JSONObject {
        val status=c.responseCode
        if(status!=200 && status!=202){
            val reason=when(status){
                401->"Code ESP32 ou token VPN incorrect"
                403->"Autorisation d'écriture ou confirmation manquante"
                404->"Route Deye absente du firmware VPN ou ESP"
                409->"Action occupée ou paramètres électriques non validés"
                502->"ESP32 Deye inaccessible via VPN"
                503->"ESP32 Deye non découvert sur cet emplacement"
                else->"HTTP "+status
            }
            throw java.io.IOException("Lecteur Deye : "+reason)
        }
        val body=c.inputStream.bufferedReader().use{it.readText().take(32769)}
        require(body.length in 2..32768){"Réponse Deye vide ou trop volumineuse"}
        val result=JSONObject(body)
        require(result.optString("product")=="smart-energy-deye-s3" ||
            (result.optBoolean("accepted") &&
             result.optString("operation")=="check_and_install")){
            "Lecteur ESP32 Deye non reconnu"
        }
        return result
    }
    fun get(slot:Int,path:String,host:String,code:String,
            vpn:VoltronicVpnRoute?,wifi:Network?):JSONObject {
        require(path in setOf("info","telemetry","details","device-config","ota/status"))
        val c=connection(slot,path,"GET",host,code,vpn,wifi)
        return try{parse(c)}finally{c.disconnect()}
    }
    private fun post(slot:Int,path:String,body:JSONObject,host:String,
            code:String,vpn:VoltronicVpnRoute?,wifi:Network?):JSONObject {
        require(path in setOf("device-config","ota/request","parameters/change"))
        require(body.optBoolean("confirmed")){"Confirmation manquante"}
        val bytes=body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 2..650)
        val c=connection(slot,path,"POST",host,code,vpn,wifi)
        return try{
            c.doOutput=true;c.readTimeout=30000
            c.setRequestProperty("Content-Type","application/json")
            c.setRequestProperty(if(vpn!=null)"X-Remote-Write-Confirmed"
                else "X-Write-Confirmed","yes")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use{it.write(bytes)}
            parse(c)
        }finally{c.disconnect()}
    }
    fun saveConfig(slot:Int,host:String,code:String,
                   vpn:VoltronicVpnRoute?,wifi:Network?,
                   ip:String,port:Int,unit:Int,dongleSerial:String,
                   inverterSerial:String):JSONObject {
        require(SunSpecReadOnly.isPrivateIPv4(ip))
        require(port in 1..65535 && unit in 1..247)
        require(dongleSerial.matches(Regex("[0-9]{1,10}")) &&
            (dongleSerial.toLongOrNull()?:0L) in 1L..4294967295L)
        require(inverterSerial.matches(Regex("[0-9]{1,32}")))
        val body=JSONObject().put("dongle_ip",ip)
            .put("dongle_port",port).put("modbus_id",unit)
            .put("dongle_serial",dongleSerial)
            .put("inverter_serial",inverterSerial).put("confirmed",true)
        val result=post(slot,"device-config",body,host,code,vpn,wifi)
        require(result.optBoolean("saved") &&
            result.optString("dongle_ip")==ip &&
            result.optInt("dongle_port")==port &&
            result.optInt("modbus_id")==unit &&
            result.optString("dongle_serial")==dongleSerial &&
            result.optString("inverter_serial")==inverterSerial){
                "ESP32 Deye : paramètres non confirmés en NVS"
            }
        return result
    }
    /** Small command only: ESP32 fetches firmware on house Wi-Fi directly. */
    fun requestOta(slot:Int,host:String,code:String,
            vpn:VoltronicVpnRoute?,wifi:Network?):JSONObject {
        return post(slot,"ota/request",
            JSONObject().put("action","check_and_install").put("confirmed",true),
            host,code,vpn,wifi)
    }
}
