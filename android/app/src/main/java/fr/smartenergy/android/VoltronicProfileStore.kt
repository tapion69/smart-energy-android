package fr.smartenergy.android

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent, per-slot Voltronic capability and settings profile.
 * Export is deliberately free of IPs, access tokens, Wi-Fi details, MACs,
 * inverter serial numbers and cached live telemetry.
 *
 * A backup never executes SETs or restores hardware parameters.
 */
class VoltronicProfileStore(private val prefs:SharedPreferences) {
    companion object {
        const val FORMAT="smart-energy-voltronic-profiles"
        const val SCHEMA=1
        private const val KEY="voltronic_native_profiles_v1"
        private const val MAX_IMPORT_BYTES=65536
        private val PARAMETERS=listOf(
            "battery_recharge_voltage_v","battery_under_voltage_v",
            "battery_bulk_voltage_v","battery_float_voltage_v",
            "battery_type_code","max_ac_charging_current_a",
            "max_charging_current_a","output_source_priority_code",
            "charger_source_priority_code","battery_re_discharge_voltage_v",
            "max_discharging_current_a","battery_recharge_capacity_pct",
            "battery_redischarge_capacity_pct","battery_under_capacity_pct"
        )
        private val FLAGS=listOf(
            "qpiri_available","qdop_available","qpiws_available",
            "qpigs2_available"
        )
        private val VERIFIED_KEYS=setOf(
            "POP_BASE","PCP_BASE","PCP_STD","PBT_BASE",
            "RECHARGE_PBCV_BASE","REDISCHARGE_PBDV_BASE",
            "UNDERVOLT_PSDV_BASE","BULK_PCVV_BASE","FLOAT_PBFT_BASE",
            "MNCHGC_BASE","MUCHGC_BASE","PBATMAXDISC_BASE",
            "PBCC_BASE","PBDC_BASE","PSDC_BASE"
        )
        private fun short(value:String):String=
            value.trim().take(96).filter{it.code in 32..126}
    }
    private fun current():JSONObject=try {
        JSONObject(prefs.getString(KEY,"{}") ?: "{}")
    } catch(_:Exception) {JSONObject()}
    private fun safeParameters(details:JSONObject):JSONObject {
        val result=JSONObject()
        val source=details.optJSONObject("parameters")?:return result
        for(group in listOf("qpiri","qdop")) {
            val data=source.optJSONObject(group)?:continue
            val fields=JSONObject()
            for(name in PARAMETERS) {
                if(!data.has(name)||data.isNull(name))continue
                val v=data.optDouble(name,Double.NaN)
                if(v.isFinite())fields.put(name,v)
            }
            fields.put("field_count",data.optJSONArray("raw_fields")?.length()?:0)
            result.put(group,fields)
        }
        return result
    }
    private fun safeEntry(slot:Int,model:String,firmware:String,remote:String,
                          capabilities:JSONObject,parameters:JSONObject,
                          learned:JSONObject):JSONObject =
        JSONObject().put("slot",slot)
            .put("model",short(model)).put("firmware_main",short(firmware))
            .put("firmware_remote",short(remote))
            .put("capabilities",capabilities)
            .put("parameters",parameters)
            .put("verified_candidates",learned)

    /** Learn observed capabilities/parameters automatically, but never a write opcode. */
    fun observe(slot:Int,snapshot:JSONObject):Boolean {
        if(slot !in 1..3 || !snapshot.optBoolean("configured") ||
            !snapshot.optBoolean("nativeDetailsFresh"))return false
        val detail=snapshot.optJSONObject("nativeDetails")?:return false
        val id=detail.optJSONObject("identification")
        val model=short(id?.optString("model","")?:"")
        val firmware=short(id?.optString("firmware_main","")?:"")
        val remote=short(id?.optString("firmware_remote","")?:"")
        val caps=JSONObject()
        for(flag in FLAGS)if(detail.has(flag))
            caps.put(flag,detail.optBoolean(flag))
        val settings=safeParameters(detail)
        if(model.isBlank() && firmware.isBlank() && caps.length()==0 &&
            settings.length()==0)return false
        val root=current()
        val name="inv$slot"
        val older=root.optJSONObject(name)
        val sameMachine=older!=null &&
            older.optString("model")==model &&
            older.optString("firmware_main")==firmware
        val candidates=if(sameMachine)
            older?.optJSONObject("verified_candidates")?:JSONObject()
            else JSONObject()
        val updated=safeEntry(slot,model,firmware,remote,caps,settings,candidates)
        if(older?.toString()==updated.toString())return false
        root.put(name,updated)
        prefs.edit().putString(KEY,root.toString()).apply()
        return true
    }
    /** Called ONLY after a fresh post-SET QPIRI/QDOP verifies the requested value. */
    fun recordVerified(slot:Int,param:String,candidateKey:String,
                       currentModel:String,currentFirmware:String):Boolean {
        if(slot !in 1..3 || param !in VoltronicEditPlanner.supportedParameters()
            || candidateKey !in VERIFIED_KEYS)return false
        val root=current()
        val saved=root.optJSONObject("inv$slot")?:return false
        if(saved.optString("model")!=short(currentModel) ||
            saved.optString("firmware_main")!=short(currentFirmware))return false
        val learned=saved.optJSONObject("verified_candidates")?:JSONObject()
        learned.put(param,candidateKey)
        saved.put("verified_candidates",learned)
        prefs.edit().putString(KEY,root.toString()).apply()
        return true
    }
    fun exportedJson():String {
        val source=current()
        val profiles=JSONArray()
        for(slot in 1..3) {
            val entry=source.optJSONObject("inv$slot")?:continue
            profiles.put(safeImportEntry(entry,slot))
        }
        return JSONObject().put("format",FORMAT).put("schema",SCHEMA)
            .put("profiles",profiles).toString(2)
    }
    private fun safeImportEntry(source:JSONObject,slot:Int):JSONObject {
        val caps=JSONObject()
        val flags=source.optJSONObject("capabilities")
        for(key in FLAGS)if(flags?.has(key)==true)caps.put(key,flags.optBoolean(key))
        val settings=JSONObject()
        val raw=source.optJSONObject("parameters")
        for(group in listOf("qpiri","qdop")){
            val values=raw?.optJSONObject(group)?:continue
            val clean=JSONObject()
            for(key in PARAMETERS)if(values.has(key) && !values.isNull(key)){
                val x=values.optDouble(key,Double.NaN)
                if(x.isFinite())clean.put(key,x)
            }
            val count=values.optInt("field_count",0).coerceIn(0,48)
            clean.put("field_count",count)
            settings.put(group,clean)
        }
        // Imported mappings are kept as records; they never enable a write.
        val learned=JSONObject()
        val incoming=source.optJSONObject("verified_candidates")
        if(incoming!=null)for(param in VoltronicEditPlanner.supportedParameters()){
            val key=incoming.optString(param,"")
            if(key in VERIFIED_KEYS)learned.put(param,key)
        }
        return safeEntry(slot,
            source.optString("model",""),source.optString("firmware_main",""),
            source.optString("firmware_remote",""),caps,settings,learned)
    }
    /** Sanitize and inspect before asking the user to approve the import. */
    fun previewImport(text:String):Pair<Int,String> {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_IMPORT_BYTES){
            "Fichier trop volumineux"}
        val root=JSONObject(text)
        require(root.optString("format")==FORMAT && root.optInt("schema")==SCHEMA){
            "Format de profil incompatible"}
        val entries=root.optJSONArray("profiles")
            ?:throw IllegalArgumentException("Profils absents")
        require(entries.length() in 1..3){"Entre 1 et 3 profils attendus"}
        val seen=mutableSetOf<Int>()
        val labels=mutableListOf<String>()
        for(i in 0 until entries.length()){
            val entry=entries.optJSONObject(i)
                ?:throw IllegalArgumentException("Profil invalide")
            val slot=entry.optInt("slot",0)
            require(slot in 1..3 && seen.add(slot)){"Numéro INV invalide ou dupliqué"}
            val p=safeImportEntry(entry,slot)
            labels.add("INV$slot : "+p.optString("model","modèle inconnu"))
        }
        return entries.length() to labels.joinToString(" · ")
    }
    /** Imports only local references: NEVER sends HTTP/UART commands. */
    fun importApproved(text:String):Int {
        val (count,_)=previewImport(text)
        val backup=JSONObject(text).getJSONArray("profiles")
        val merged=current()
        for(i in 0 until backup.length()){
            val item=backup.getJSONObject(i)
            val slot=item.getInt("slot")
            val current=merged.optJSONObject("inv$slot")
            // Do not let an imported profile replace a different verified machine.
            if(current!=null && current.optString("model").isNotBlank() &&
                current.optString("model")!=item.optString("model"))
                throw IllegalArgumentException("INV$slot : modèle différent, import refusé")
        }
        for(i in 0 until backup.length()){
            val item=backup.getJSONObject(i)
            val slot=item.getInt("slot")
            merged.put("inv$slot",safeImportEntry(item,slot))
        }
        prefs.edit().putString(KEY,merged.toString()).apply()
        return count
    }
}
