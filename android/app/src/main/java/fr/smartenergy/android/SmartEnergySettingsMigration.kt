package fr.smartenergy.android

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Consented, non-secret migration from an old debug APK.
 * Old APKs were signed using ephemeral GitHub runner keys; Android CANNOT
 * update them in place. Import only whitelisted ordinary settings, never
 * Android Keystore ciphertext. Codes and VPN token must be entered once in
 * the permanently signed installation.
 */
internal object SmartEnergySettingsMigration {
    private const val FORMAT="smart-energy-settings-migration"
    private const val SCHEMA=1
    private const val MAX_BYTES=65536
    private val safeStrings=setOf(
        "device_id","setup_ssid","local_ip","paired_bms_json","known_jk_macs",
        "remote_gateway_ip","connection_preference","energy_dashboard_style",
        "inverter_probe_ip","inverter_probe_type",
        "inverter_2_ip","inverter_3_ip")
    private val safeBooleans=setOf(
        "remote_gateway_enabled","remote_voltronic_enabled",
        "inverter_probe_validated","inverter_2_validated","inverter_3_validated",
        "bms_alarm_notifications","debug_enabled",
        "alert_bms_alarm_enabled","alert_global_soc_enabled",
        "alert_bms_soc_enabled","alert_cell_high_enabled","alert_temp_high_enabled")
    private val safeInts=setOf(
        "active_reader_slot","inverter_probe_port",
        "inverter_2_port","inverter_3_port",
        "alert_global_soc_below","alert_bms_soc_below")
    private val safeFloats=setOf("alert_cell_high_above_v","alert_temp_high_above_c")
    private val names=Regex("^bms_name_[A-Fa-f0-9]{12}$")
    private val reader=Regex("^reader_[1-3]_(device_id|setup_ssid|local_ip|paired_bms_json|known_jk_macs)$")

    fun typeFor(key:String):String?=when {
        key in safeStrings || names.matches(key) || reader.matches(key) -> "string"
        key in safeBooleans -> "boolean"
        key in safeInts -> "int"
        key in safeFloats -> "float"
        else -> null
    }

    fun sanitized(prefs:SharedPreferences):String {
        val items=JSONObject()
        for((key,value) in prefs.all){
            when(typeFor(key)){
                "string" -> if(value is String && value.length<=8192)
                    items.put(key,value)
                "boolean" -> if(value is Boolean)items.put(key,value)
                "int" -> if(value is Int)items.put(key,value)
                "float" -> if(value is Float && value.isFinite())
                    items.put(key,value.toDouble())
            }
        }
        return JSONObject().put("format",FORMAT).put("schema",SCHEMA)
            .put("settings",items).toString(2)
    }

    /** Reject unknown keys (including encrypted credential keys). */
    fun preview(raw:String):Pair<Int,String> {
        require(raw.toByteArray(Charsets.UTF_8).size<=MAX_BYTES){
            "Sauvegarde trop volumineuse"}
        val doc=JSONObject(raw)
        require(doc.optString("format")==FORMAT && doc.optInt("schema")==SCHEMA){
            "Ce fichier n'est pas une sauvegarde de migration Smart Energy"}
        val items=doc.optJSONObject("settings")
            ?:throw IllegalArgumentException("Préférences absentes")
        require(items.length() in 1..120){"Nombre de préférences invalide"}
        var jk=0
        val iter=items.keys()
        while(iter.hasNext()){
            val key=iter.next()
            val t=typeFor(key) ?:throw IllegalArgumentException(
                "Clé de configuration non autorisée")
            val value=items.get(key)
            when(t){
                "string" -> require(value is String && value.length<=8192){
                    "Champ texte invalide"}
                "boolean" -> require(value is Boolean){"Case invalide"}
                "int" -> require(value is Number &&
                    value.toDouble().isFinite() &&
                    value.toDouble()%1.0==0.0 &&
                    value.toLong() in -100000..100000){"Nombre invalide"}
                "float" -> require(value is Number &&
                    value.toDouble().isFinite() &&
                    value.toDouble() in 0.0..120.0){"Seuil invalide"}
            }
            if(key=="paired_bms_json" ||
                key.matches(Regex("^reader_[1-3]_paired_bms_json$")))jk++
        }
        return items.length() to
            "$jk liste(s) de batteries · IP, noms et préférences restaurables. "+
            "Les codes JK, le token VPN et les codes Voltronic ne sont pas dans le fichier."
    }

    /** Never writes a secret or starts any network/BLE operation. */
    fun importApproved(prefs:SharedPreferences,raw:String):Int {
        val (count,_)=preview(raw)
        val items=JSONObject(raw).getJSONObject("settings")
        val editor=prefs.edit()
        val iter=items.keys()
        while(iter.hasNext()){
            val key=iter.next()
            val value=items.get(key)
            when(typeFor(key)){
                "string" -> editor.putString(key,value as String)
                "boolean" -> editor.putBoolean(key,value as Boolean)
                "int" -> editor.putInt(key,(value as Number).toInt())
                "float" -> editor.putFloat(key,(value as Number).toFloat())
            }
        }
        require(editor.commit()){"Échec de l'enregistrement local"}
        return count
    }
}
