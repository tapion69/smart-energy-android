package fr.smartenergy.android

import android.content.SharedPreferences
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.LocalDate
import java.time.ZoneOffset
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
    private val energyWorker:ExecutorService,
    private val localWifiNetwork:()->Network?,
    private val vpnRoute:()->VoltronicVpnRoute?,
    private val updated:()->Unit
) {
    private val mainHandler=Handler(Looper.getMainLooper())
    private val states=Array(3){ JSONObject().put("configured",false) }
    // Values returned by the inverter over GET /details; no custom integrations.
    private val nativeDetails=arrayOfNulls<JSONObject>(3)
    private val nativeDetailsReceivedAt=LongArray(3)
    private var lastPoll=0L
    private var lastEnergyPoll=0L
    private var energyBusy=false
    private var busy=false
    private val httpTotalErrors=IntArray(3)
    private val lastHttpKinds=arrayOfNulls<String>(3)

    fun configured(slot:Int):Boolean {
        require(slot in 1..3)
        return if(slot==1) prefs.getBoolean("inverter_probe_validated",false)
               else prefs.getBoolean("inverter_${slot}_validated",false)
    }
    fun hasInverters():Boolean=(1..3).any { configured(it) }
    fun snapshotFor(slot:Int):JSONObject {
        if(!configured(slot)) return JSONObject().put("configured",false).put("slot",slot)
        val s=states[slot-1]
        val result=if(s.optBoolean("configured"))JSONObject(s.toString())
               else JSONObject().put("configured",true).put("slot",slot)
                   .put("fresh",false).put("name","Onduleur INV$slot")
        // Cached values never become 'fresh' just because the screen redraws.
        val stamp=result.optLong("sampleAtMs",-1L)
        val age=result.optLong("qpigsAgeMs",-1L)
        if(result.optBoolean("fresh") && stamp>0L && age>=0L) {
            val measuredAge=age+(SystemClock.elapsedRealtime()-stamp).coerceAtLeast(0L)
            result.put("effectiveSampleAgeMs",measuredAge)
            if(measuredAge>VoltronicHttpResilience.MAX_SAMPLE_AGE_MS) {
                result.put("fresh",false).put("transientHttpError",false)
                    .put("detail","Dernière mesure trop ancienne : "+(measuredAge/1000)+" s")
            }
        }
        val details=nativeDetails[slot-1]
        val received=nativeDetailsReceivedAt[slot-1]
        if(details!=null && received>0L) {
            val detailsAge=(SystemClock.elapsedRealtime()-received).coerceAtLeast(0L)
            val detailsFresh=detailsAge<=900000L
            result.put("nativeDetails",details)
                .put("nativeDetailsAgeMs",detailsAge)
                .put("nativeDetailsFresh",detailsFresh)
            if(detailsFresh) {
                val mode=details.optString("operation_mode","")
                if(mode.isNotEmpty())result.put("operationMode",mode)
            }
            if(detailsFresh && result.optBoolean("fresh")) {
                fun nativeToday(source:String):Double? {
                    val key=details.optJSONObject("energy_query_keys")
                        ?.optJSONObject(source)?.optString("day_utc","")
                    val today=LocalDate.now(ZoneOffset.UTC).toString().replace("-","")
                    if(key!=today)return null
                    return details.optJSONObject("energy_wh")?.optJSONObject(source)
                        ?.optDouble("day_utc",Double.NaN)
                        ?.takeIf{it.isFinite()&&it>=0.0}
                }
                nativeToday("pv")?.let{result.put("pvDayKwh",it/1000.0)}
                nativeToday("load")?.let{result.put("loadDayKwh",it/1000.0)}
            }
        }
        return result
    }
    fun snapshot():JSONArray {
        val out=JSONArray()
        for(slot in 1..3) if(configured(slot))out.put(snapshotFor(slot))
        return out
    }
    fun changed() {
        lastPoll=0L
        lastEnergyPoll=0L
        for(i in states.indices) {
            states[i]=JSONObject().put("configured",false)
            nativeDetails[i]=null
            nativeDetailsReceivedAt[i]=0L
            httpTotalErrors[i]=0
            lastHttpKinds[i]=null
        }
        updated()
    }
    fun poll(now:Long,wifiNetwork:Network?) {
        if(!hasInverters()) {
            if(now-lastPoll>20000L) {lastPoll=now;updated()}
            return
        }
        val privateGateway=vpnRoute()
        pollNativeDetails(now,wifiNetwork,privateGateway)
        // Android UI tick is 2.5s; a 5s HTTP rate remains gentle on ESP32.
        if(busy || (lastPoll>0L && now-lastPoll<5000L))return
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
            var needsShortRetry=false
            for(c in configs) {
                val prior=previous[c.slot-1]
                val next=when {
                    c.kind!="voltronic" -> JSONObject().put("configured",true)
                        .put("slot",c.slot).put("fresh",false)
                        .put("name","SunSpec INV${c.slot}")
                        .put("detail","Découverte seulement : aucune mesure disponible")
                    (wifiNetwork==null && privateGateway==null) ||
                        (privateGateway==null && !SunSpecReadOnly.isPrivateIPv4(c.host)) ||
                        c.port !in 1..65535 || c.code.length !in 8..32 ->
                        JSONObject(prior.toString()).put("configured",true)
                            .put("slot",c.slot).put("fresh",false)
                            .put("detail",if(wifiNetwork==null && privateGateway==null)
                                "Wi-Fi local requis · VPN Voltronic non configuré"
                                else "Lecteur à associer")
                    else -> try {
                        val raw=if(privateGateway!=null)
                            VoltronicVpnReadOnlyClient.read(c.slot,false,privateGateway)
                        else VoltronicReadOnlyClient.telemetry(
                            c.host,c.code,c.port,wifiNetwork)
                        val nextReading=if(raw.optBoolean("available") &&
                            raw.optJSONObject("values")!=null)
                            mapTelemetry(c.slot,raw)
                        else JSONObject(prior.toString()).put("configured",true)
                            .put("slot",c.slot).put("fresh",false)
                            .put("transportReachable",true)
                            .put("qpigsAgeMs",raw.optLong("age_ms",-1L))
                            .put("serialTimeouts",raw.optLong("timeouts",0L))
                            .put("serialNoRxTimeouts",raw.optLong("no_rx_timeouts",0L))
                            .put("serialCrcRejects",raw.optLong("crc_rejects",0L))
                            .put("detail","ESP32 joignable · QPIGS en retard")
                        nextReading.put("httpConsecutiveErrors",0)
                            .put("httpErrorsTotal",httpTotalErrors[c.slot-1])
                            .put("transientHttpError",false)
                            .put("voltronicTransport",if(privateGateway!=null)"VPN Tailscale" else "Wi-Fi local")
                        lastHttpKinds[c.slot-1]?.let {
                            nextReading.put("lastHttpErrorKind",it)
                        }
                        nextReading
                    } catch(ex:Exception) {
                        val errors=prior.optInt("httpConsecutiveErrors",0)+1
                        httpTotalErrors[c.slot-1]++
                        val errorKind=VoltronicHttpResilience.errorKind(ex)
                        lastHttpKinds[c.slot-1]=errorKind
                        val recent=VoltronicHttpResilience.recentOnFailure(
                            errors,prior.optLong("sampleAtMs",-1L),
                            prior.optLong("qpigsAgeMs",-1L),SystemClock.elapsedRealtime())
                        if(VoltronicHttpResilience.quickRetry(errors))
                            needsShortRetry=true
                        JSONObject(prior.toString()).put("configured",true)
                            .put("slot",c.slot).put("fresh",recent)
                            .put("transportReachable",false)
                            .put("transientHttpError",recent)
                            .put("httpConsecutiveErrors",errors)
                            .put("httpErrorsTotal",httpTotalErrors[c.slot-1])
                            .put("lastHttpErrorKind",errorKind)
                            .put("detail",if(ex is VoltronicVpnHttpException)
                                ex.userHint
                                else if(recent)
                                "HTTP instable · dernière mesure récente conservée"
                                else if(errors<VoltronicHttpResilience.OFFLINE_AFTER)
                                "HTTP instable · lecture en cours de reprise"
                                else "ESP32 HTTP inaccessible · dernière mesure conservée")
                    }
                }
                results[c.slot-1]=next
            }
            mainHandler.post {
                for(i in results.indices)states[i]=results[i]
                busy=false
                // Short bounded retry on next 2.5s UI tick after 1-2 misses.
                if(needsShortRetry)
                    lastPoll=SystemClock.elapsedRealtime()-6000L
                updated()
            }
        }
    }
    private data class Config(val slot:Int,val kind:String,val host:String,
                              val port:Int,val code:String)

    private fun pollNativeDetails(now:Long,network:Network?,gateway:VoltronicVpnRoute?) {
        if((network==null && gateway==null) || energyBusy ||
            (lastEnergyPoll>0L && now-lastEnergyPoll<90000L))return
        val configs=(1..3).filter {configured(it)}.mapNotNull { slot ->
            val type=if(slot==1)prefs.getString("inverter_probe_type","voltronic")
                else "voltronic"
            if(type!="voltronic")return@mapNotNull null
            val host=if(slot==1)prefs.getString("inverter_probe_ip","") ?: ""
                else prefs.getString("inverter_"+slot+"_ip","") ?: ""
            val port=if(slot==1)prefs.getInt("inverter_probe_port",18081)
                else prefs.getInt("inverter_"+slot+"_port",18081)
            val code=readCode(if(slot==1)"premium_voltronic_code_cipher"
                else "premium_voltronic_"+slot+"_code_cipher")
            if((gateway==null && !SunSpecReadOnly.isPrivateIPv4(host)) ||
                port !in 1..65535 ||
                (gateway==null && code.length !in 8..32))
                null else Config(slot,"voltronic",host,port,code)
        }
        if(configs.isEmpty())return
        lastEnergyPoll=now
        energyBusy=true
        energyWorker.execute {
            val natives=mutableMapOf<Int,JSONObject>()
            for(config in configs) {
                try {
                    val obj=if(gateway!=null)
                        VoltronicVpnReadOnlyClient.read(config.slot,true,gateway)
                    else VoltronicReadOnlyClient.details(
                        config.host,config.code,config.port,network)
                    natives[config.slot]=obj
                } catch(_:Exception) {
                    // Optional details; never block real-time QPIGS.
                }
            }
            mainHandler.post {
                val stamp=SystemClock.elapsedRealtime()
                for((slot,detail) in natives) {
                    nativeDetails[slot-1]=detail
                    nativeDetailsReceivedAt[slot-1]=stamp
                }
                energyBusy=false
                updated()
            }
        }
    }
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
        copy("output_va","apparentPowerVa",0.0,30000.0)
        copy("load_percent","loadPercent",0.0,100.0)
        copy("output_voltage_v","outputVoltageV",0.0,300.0)
        copy("output_current_a","outputCurrentA",0.0,200.0)
        copy("output_hz","outputHz",40.0,70.0)
        copy("grid_voltage_v","gridVoltageV",0.0,300.0)
        copy("grid_hz","gridFrequencyHz",0.0,70.0)
        copy("battery_voltage_v","batteryVoltageV",0.0,80.0)
        copy("battery_charge_a","batteryChargeCurrentA",0.0,400.0)
        copy("battery_discharge_a","batteryDischargeCurrentA",0.0,400.0)
        copy("battery_voltage_from_scc_v","batterySccVoltageV",0.0,80.0)
        copy("battery_net_current_a","batteryCurrentA",-400.0,400.0)
        copy("battery_soc_pct","inverterBatterySoc",0.0,100.0)
        copy("heatsink_temp_c","temperatureC",-40.0,140.0)
        copy("bus_voltage_v","busVoltageV",0.0,600.0)
        copy("output_current_a","outputCurrentEstimateA",0.0,200.0)
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
        // /telemetry has no energy_wh; only the optional /details PV counter is used.
        out.put("fresh",true).put("sampleAtMs",SystemClock.elapsedRealtime())
            .put("firmwareVersion",raw.optString("version",""))
            .put("qpigsAgeMs",age).put("transportReachable",true)
        // Optional fields require Voltronic S3 0.2.1+; older firmware
        // continues to function, and absent counters stay unavailable.
        for((source,target) in listOf(
            "wifi_disconnect_count" to "s3WifiDisconnects",
            "wifi_last_disconnect_reason" to "s3WifiLastReason",
            "wifi_got_ip_count" to "s3WifiGotIp",
            "http_requests" to "s3HttpRequests",
            "http_telemetry_requests" to "s3HttpTelemetryRequests",
            "http_send_failures" to "s3HttpSendFailures",
            "wifi_rssi_dbm" to "s3WifiRssi",
            "free_heap_bytes" to "s3FreeHeapBytes",
            "minimum_free_heap_bytes" to "s3MinFreeHeapBytes",
            "uptime_ms" to "s3UptimeMs",
            "qpigs_requests" to "s3QpigsRequests",
            "qpigs2_requests" to "s3Qpigs2Requests",
            "qpigs_actual_request_interval_ms" to "s3QpigsIntervalMs",
            "qpigs2_actual_request_interval_ms" to "s3Qpigs2IntervalMs",
            "last_serial_exchange_ms" to "s3LastExchangeMs")) {
            if(raw.has(source) && !raw.isNull(source))
                raw.optDouble(source,Double.NaN).takeIf{it.isFinite()}?.let{
                    out.put(target,it)
                }
        }
        if(raw.has("wifi_connected"))
            out.put("s3WifiConnected",raw.optBoolean("wifi_connected"))
        out.put("httpConsecutiveErrors",0)
            .put("serialTimeouts",raw.optLong("timeouts",0L))
            .put("serialNoRxTimeouts",raw.optLong("no_rx_timeouts",0L))
            .put("serialCrcRejects",raw.optLong("crc_rejects",0L))
            .put("detail","Lecture fraîche")
        return out
    }
}
