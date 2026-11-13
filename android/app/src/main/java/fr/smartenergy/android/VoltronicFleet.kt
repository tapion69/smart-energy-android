package fr.smartenergy.android

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import kotlin.math.abs

/**
 * Three independently configured read-only Voltronic bridges.
 * Only this class manages inverter snapshots; it does not read or mutate JK data.
 */
class VoltronicFleet(
    private val prefs:SharedPreferences,
    private val readCode:(String)->String,
    private val worker:ExecutorService,
    private val updated:()->Unit
) {
    private val mainHandler=Handler(Looper.getMainLooper())
    private val states=Array(3){ JSONObject().put("configured",false) }
    private var lastPoll=0L
    private var busy=false

    fun configured(slot:Int):Boolean {
        require(slot in 1..3)
        return if(slot==1) prefs.getBoolean("inverter_probe_validated",false)
               else prefs.getBoolean("inverter_${slot}_validated",false)
    }
    fun hasInverters():Boolean=(1..3).any { configured(it) }
    fun snapshotFor(slot:Int):JSONObject {
        if(!configured(slot)) return JSONObject().put("configured",false).put("slot",slot)
        val s=states[slot-1]
        return if(s.optBoolean("configured"))s
               else JSONObject().put("configured",true).put("slot",slot)
                   .put("fresh",false).put("name","Onduleur INV$slot")
    }
    fun snapshot():JSONArray {
        val out=JSONArray()
        for(slot in 1..3) if(configured(slot))out.put(snapshotFor(slot))
        return out
    }
    fun changed() { lastPoll=0L; updated() }
    fun poll(now:Long,vpnBlocked:Boolean) {
        if(!hasInverters()) {
            if(now-lastPoll>20000L) {lastPoll=now;updated()}
            return
        }
        if(busy || (lastPoll>0L && now-lastPoll<8000L))return
        lastPoll=now
        val configs=(1..3).filter {configured(it)}.map { slot ->
            val kind=if(slot==1) prefs.getString("inverter_probe_type","voltronic")
                ?: "voltronic" else "voltronic"
            val host=if(slot==1)prefs.getString("inverter_probe_ip","") ?: ""
                else prefs.getString("inverter_${slot}_ip","") ?: ""
            val port=if(slot==1)prefs.getInt("inverter_probe_port",18081)
                else prefs.getInt("inverter_${slot}_port",18081)
            val code=readCode(if(slot==1)"premium_voltronic_code_cipher"
                else "premium_voltronic_${slot}_code_cipher")
            Config(slot,kind,host,port,code)
        }
        val previous=Array(3){ JSONObject(states[it].toString()) }
        busy=true
        worker.execute {
            val results=Array(3){ previous[it] }
            for(c in configs) {
                val prior=previous[c.slot-1]
                val next=when {
                    c.kind!="voltronic" -> JSONObject().put("configured",true)
                        .put("slot",c.slot).put("fresh",false)
                        .put("name","SunSpec INV${c.slot}")
                        .put("detail","Découverte seulement : aucune mesure disponible")
                    vpnBlocked || !SunSpecReadOnly.isPrivateIPv4(c.host) ||
                        c.port !in 1..65535 || c.code.length !in 8..32 ->
                        JSONObject(prior.toString()).put("configured",true)
                            .put("slot",c.slot).put("fresh",false)
                            .put("detail",if(vpnBlocked)
                                "L'accès VPN Voltronic n'est pas encore relayé"
                                else "Lecteur à associer")
                    else -> try {
                        mapTelemetry(c.slot,VoltronicReadOnlyClient.telemetry(c.host,c.code,c.port))
                    } catch(_:Exception) {
                        JSONObject(prior.toString()).put("configured",true)
                            .put("slot",c.slot).put("fresh",false)
                            .put("detail","Liaison indisponible · dernière lecture conservée")
                    }
                }
                results[c.slot-1]=next
            }
            mainHandler.post {
                for(i in results.indices)states[i]=results[i]
                busy=false
                updated()
            }
        }
    }
    private data class Config(val slot:Int,val kind:String,val host:String,
                              val port:Int,val code:String)
    private fun mapTelemetry(slot:Int,raw:JSONObject):JSONObject {
        val out=JSONObject().put("configured",true).put("slot",slot)
            .put("name","Voltronic INV$slot")
        val v=raw.optJSONObject("values")
        val age=raw.optLong("age_ms",-1)
        if(v==null || !raw.optBoolean("available") || age !in 0L..10000L)
            return out.put("fresh",false).put("detail","Mesures non fraîches")
        fun num(k:String):Double? {
            if(!v.has(k)||v.isNull(k))return null
            return v.optDouble(k,Double.NaN).takeIf { it.isFinite() }
        }
        fun copy(k:String,target:String,min:Double,max:Double) {
            num(k)?.takeIf { it in min..max }?.let {out.put(target,it)}
        }
        copy("output_w","loadW",0.0,30000.0)
        copy("output_voltage_v","outputVoltageV",0.0,300.0)
        copy("output_current_a","outputCurrentA",0.0,200.0)
        copy("output_hz","outputHz",40.0,70.0)
        copy("grid_voltage_v","gridVoltageV",0.0,300.0)
        copy("battery_voltage_v","batteryVoltageV",0.0,80.0)
        copy("battery_net_current_a","batteryCurrentA",-400.0,400.0)
        copy("battery_soc_pct","inverterBatterySoc",0.0,100.0)
        copy("heatsink_temp_c","temperatureC",-40.0,140.0)
        copy("bus_voltage_v","busVoltageV",0.0,600.0)
        out.put("operationMode",raw.optString("operation_mode",""))
        val pv=JSONArray()
        val v1=num("pv1_voltage_v")?.takeIf {it in 0.0..900.0}
        val a1=num("pv1_current_a")?.takeIf {it in 0.0..100.0}
        val native1=num("pv1_power_native_w")?.takeIf {it in 0.0..30000.0}
        val w1=native1 ?: if(v1!=null && a1!=null)v1*a1 else null
        if(v1!=null || a1!=null || native1!=null) {
            val ch=JSONObject().put("index",1).put("derived",native1==null)
            if(v1!=null)ch.put("voltageV",v1)
            if(a1!=null)ch.put("currentA",a1)
            if(w1!=null)ch.put("powerW",w1)
            pv.put(ch)
        }
        val v2=num("pv2_voltage_v")?.takeIf {it in 0.0..900.0}
        val a2=num("pv2_current_a")?.takeIf {it in 0.0..100.0}
        val w2=num("pv2_power_w")?.takeIf {it in 0.0..30000.0}
        // QPIGS2 may NAK: never manufacture a second MPPT.
        if(v2!=null || a2!=null || w2!=null) {
            val ch=JSONObject().put("index",2).put("derived",false)
            if(v2!=null)ch.put("voltageV",v2)
            if(a2!=null)ch.put("currentA",a2)
            if(w2!=null)ch.put("powerW",w2)
            pv.put(ch)
        }
        out.put("pvChannels",pv)
        if(w1!=null || w2!=null)
            out.put("pvPowerW",(w1 ?: 0.0)+(w2 ?: 0.0))
        out.put("pvPartial",w1==null || (v2!=null && w2==null))
        num("grid_power_native_w")?.takeIf {abs(it)<30000.0}?.let {
            out.put("gridPowerW",it)
            out.put("gridDetail","Puissance native, sens non vérifié")
        }
        out.put("gridDirectionVerified",false)
        val energy=raw.optJSONObject("energy_wh")
        for((s,k) in listOf("pv" to "pvDayKwh","load" to "loadDayKwh")) {
            val group=energy?.optJSONObject(s)
            if(group!=null && group.has("day_utc") && !group.isNull("day_utc"))
                group.optDouble("day_utc",Double.NaN)
                    .takeIf {it.isFinite()&&it>=0.0}?.let {out.put(k,it/1000.0)}
        }
        out.put("fresh",true).put("sampleAtMs",SystemClock.elapsedRealtime())
            .put("detail","Lecture fraîche")
        return out
    }
}
