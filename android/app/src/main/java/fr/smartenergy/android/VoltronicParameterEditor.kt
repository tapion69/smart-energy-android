package fr.smartenergy.android

import android.net.Network
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Verified semantic Voltronic parameter edits, over authenticated LAN
 * or physically enabled VPN. Raw QPIRI/QDOP fields remain in diagnostics;
 * only S3-firmware-allowlisted commands can ever be proposed for writing. */
internal object VoltronicParameterEditor {
    data class Setting(val key:String,val label:String,val value:Double,val unit:String,
                       val editable:Boolean=true)
    private data class Spec(val key:String,val label:String,val source:String,
                            val unit:String)
    private val specifications = listOf(
        // The two priorities are the most useful daily inverter controls.
        // POP/PCP opcodes and ranges are explicitly allowlisted by S3.
        Spec("output_source_priority_code","Priorité secteur / sortie","qpiri",""),
        Spec("charger_source_priority_code","Priorité de charge","qpiri",""),
        Spec("battery_recharge_voltage_v","Tension de retour réseau","qpiri","V"),
        Spec("battery_under_voltage_v","Tension de coupure batterie","qpiri","V"),
        Spec("battery_bulk_voltage_v","Tension Bulk","qpiri","V"),
        Spec("battery_float_voltage_v","Tension Float","qpiri","V"),
        Spec("battery_re_discharge_voltage_v","Tension de retour batterie","qpiri","V"),
        Spec("battery_type_code","Type de batterie (code)","qpiri",""),
        Spec("max_ac_charging_current_a","Courant maximal de charge AC","qpiri","A"),
        Spec("max_charging_current_a","Courant de charge maximal","qpiri","A"),
        Spec("max_discharging_current_a","Courant de décharge maximal (pas 10 A)","qpiri","A"),
        Spec("battery_recharge_capacity_pct","Capacité retour réseau","qdop","%"),
        Spec("battery_redischarge_capacity_pct","Capacité retour batterie","qdop","%"),
        Spec("battery_under_capacity_pct","Capacité basse","qdop","%")
    )
    fun priorityChoices(key:String):List<Pair<Double,String>> = when(key) {
        "output_source_priority_code" -> listOf(
            0.0 to "Secteur → solaire → batterie",
            1.0 to "Solaire → secteur → batterie",
            2.0 to "Solaire → batterie → secteur"
        )
        "charger_source_priority_code" -> listOf(
            1.0 to "Solaire en priorité",
            2.0 to "Solaire + secteur",
            3.0 to "Solaire uniquement"
        )
        else -> emptyList()
    }
    fun settingDescription(setting:Setting):String {
        val choice=priorityChoices(setting.key).find{
            kotlin.math.abs(it.first-setting.value)<0.01
        }?.second
        return if(choice!=null)"${setting.label} : $choice (code ${setting.value.toInt()})"
               else "${setting.label} : ${setting.value} ${setting.unit}"
    }
    private fun validateLocal(host:String,code:String,port:Int,network:Network?) {
        require(network!=null){"Wi-Fi local obligatoire (pas de VPN)"}
        require(SunSpecReadOnly.isPrivateIPv4(host)){"Adresse IPv4 locale obligatoire"}
        require(code.length in 8..32){"Code Voltronic absent"}
        require(port in 1..65535){"Port S3 invalide"}
    }
    fun current(
        host:String,code:String,port:Int,network:Network?,
        route:VoltronicVpnRoute?=null,slot:Int=1
    ):List<Setting> {
        if(route==null)validateLocal(host,code,port,network)
        val remoteWriteAllowed=route==null ||
            try { VoltronicVpnReadOnlyClient.remoteWritesEnabled(route) }
            catch(_:Exception) { false }
        val info=if(route!=null)VoltronicVpnReadOnlyClient.info(slot,route)
                 else VoltronicReadOnlyClient.probe(host,code,port,network)
        val editSupported=info.optBoolean("parameter_edit_supported",false) &&
            info.optString("parameter_edit_protocol")=="verified-write-v1"
        // Older or USB-locked gateways may still provide valid native
        // parameter reads. Never offer a write unless both sides advertise it.
        val canEdit=editSupported && remoteWriteAllowed
        val details=if(route!=null)VoltronicVpnReadOnlyClient.read(slot,true,route)
                    else VoltronicReadOnlyClient.details(host,code,port,network)
        val native=details.optJSONObject("parameters")
            ?:throw IllegalStateException("Valeurs natives indisponibles")
        val available=specifications.mapNotNull { spec ->
            val obj=native.optJSONObject(spec.source) ?:return@mapNotNull null
            if(!obj.has(spec.key)||obj.isNull(spec.key))return@mapNotNull null
            val age=obj.optLong("age_ms",Long.MAX_VALUE)
            if(age !in 0L..300000L)return@mapNotNull null
            val value=obj.optDouble(spec.key,Double.NaN)
            if(!value.isFinite())null else Setting(
                spec.key,spec.label,value,spec.unit,editable=canEdit)
        }
        // Diagnostic raw QPIRI / QDOP remains visible in the Inverter
        // details panel, NOT in this edit picker. Unknown positions are
        // never presented as editable settings.
        return available
    }
    fun change(
        host:String,code:String,port:Int,network:Network?,
        key:String,old:Double,new:Double,
        route:VoltronicVpnRoute?=null,slot:Int=1
    ):JSONObject {
        if(route==null)validateLocal(host,code,port,network)
        require(specifications.any {it.key==key}){"Paramètre inconnu"}
        require(old.isFinite()&&new.isFinite()&&old!=new){"Nouvelle valeur invalide"}
        if(route!=null)return VoltronicVpnReadOnlyClient.change(
            slot,route,key,old,new)
        val body=JSONObject().put("parameter",key).put("old",old)
            .put("value",new).put("confirmed",true).toString()
        val url=URL("http://$host:$port/api/v1/parameters/change")
        val connection=(network!!.openConnection(url)) as HttpURLConnection
        try {
            connection.requestMethod="POST"
            connection.instanceFollowRedirects=false
            connection.connectTimeout=3500
            connection.readTimeout=20000
            connection.doOutput=true
            connection.setRequestProperty("X-Setup-Code",code)
            connection.setRequestProperty("X-Write-Confirmed","yes")
            connection.setRequestProperty("Content-Type","application/json")
            connection.setRequestProperty("Cache-Control","no-store")
            val bytes=body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use {it.write(bytes)}
            require(connection.responseCode==200) {
                "Écriture refusée (HTTP ${connection.responseCode}) ; état non confirmé"
            }
            val answer=JSONObject(connection.inputStream.bufferedReader().use {
                it.readText().take(2048)
            })
            require(answer.optString("source")=="voltronic-parameter-verification") {
                "Réponse du S3 non reconnue ; état non confirmé"
            }
            return answer
        }finally {connection.disconnect()}
    }
}
