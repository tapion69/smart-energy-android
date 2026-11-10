package fr.smartenergy.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opt-in foreground monitoring of cached JK readings, not a BLE reader.
 * No measurements or history are persisted; only transitions across configured
 * SOC, cell, temperature or BMS alarm thresholds notify.
 * Android may still stop the service; force-stop and lost connectivity prevent alerts.
 */
class JkAlarmMonitorService : Service() {
    companion object {
        const val ACTION_STOP = "fr.smartenergy.android.STOP_MONITOR"
        const val ACTION_RELOAD = "fr.smartenergy.android.RELOAD_MONITOR"
        const val CHANNEL_RUNNING = "smart_energy_monitor"
        const val CHANNEL_ALARMS = "smart_energy_bms_alarms"
        private const val SERVICE_ID = 3201
    }

    private val prefs by lazy { getSharedPreferences("smart_energy_v012", Context.MODE_PRIVATE) }
    private val connectivity by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private var scheduler: ScheduledExecutorService? = null
    private val reported = mutableSetOf<String>()
    private val temperatureNames = listOf(
        "Sonde 1" to "temp1_c", "Sonde 2" to "temp2_c",
        "Sonde 3" to "temp3_c", "Sonde 4" to "temp4_c",
        "Sonde 5" to "temp5_c", "MOS" to "mos_temp_c")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RUNNING,
            "Surveillance Smart Energy", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALARMS,
            "Alarmes JK BMS", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !prefs.getBoolean(JkAlertPreferences.MONITOR, false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Start only after explicit user opt-in from the visible settings page.
        try {
            startForeground(SERVICE_ID, Notification.Builder(this, CHANNEL_RUNNING)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Smart Energy : surveillance active")
                .setContentText("Alarmes, SOC, cellules et températures · toutes les 30 s")
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .build())
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
            scheduler?.scheduleWithFixedDelay({
                try { checkThresholds() } catch (_: Exception) {
                    // A network or permission failure must not create a false alarm.
                }
            }, 0, 30, TimeUnit.SECONDS)
        } else if (intent?.action == ACTION_RELOAD) {
            // Explicit rule save: reset one-shot state and check new rules now.
            scheduler?.execute {
                reported.clear()
                try { checkThresholds() } catch (_: Exception) {}
            }
        }
        return START_STICKY
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun vpnConnected(): Boolean = try {
        connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (_: Exception) { false }

    private fun validIp(input: String, tailnet: Boolean): Boolean {
        val parts = input.split(".")
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
                    (it.toIntOrNull() ?: -1) !in 0..255 }) return false
        val oct = parts.map { it.toInt() }
        return if (tailnet) oct[0] == 100 && oct[1] in 64..127
        else oct[0] == 10 || (oct[0] == 192 && oct[1] == 168) ||
            (oct[0] == 172 && oct[1] in 16..31)
    }

    private fun decrypt(keyName: String): String {
        val encoded = prefs.getString(keyName, "") ?: ""
        if (encoded.isBlank()) return ""
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size < 29) return ""
            val store = KeyStore.getInstance("AndroidKeyStore")
            store.load(null)
            val key = store.getKey("smart_energy_pairing_v1", null) as? SecretKey
                ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun pref(slot: Int, key: String): String {
        val active = prefs.getInt("active_reader_slot", 1)
        return prefs.getString(
            if (active == slot) key else "reader_${slot}_${key}", "") ?: ""
    }

    private fun read(slot: Int, vpn: Boolean): JSONObject? {
        val preference = prefs.getString("connection_preference", "auto") ?: "auto"
        val host: String
        val code: String
        val route: String
        val header: String
        if (vpn && preference != "local" &&
            (preference == "gateway" || prefs.getBoolean("remote_gateway_enabled", false))) {
            host = prefs.getString("remote_gateway_ip", "") ?: ""
            code = decrypt("remote_gateway_token_cipher")
            if (!validIp(host, true) || code.length !in 20..128 ||
                code.any { it.code !in 33..126 }) return null
            route = JkTransportPolicy.vpnReadPath(slot, "/api/v1/telemetry") ?: return null
            header = "X-Gateway-Token"
        } else {
            // Explicit local choice may coexist with an active Android VPN.
            // Never fall back silently to LAN when VPN was selected.
            if (preference == "gateway" || (vpn && preference != "local")) return null
            host = pref(slot, "local_ip")
            code = decrypt(if (slot == prefs.getInt("active_reader_slot", 1))
                "pairing_code_cipher" else "reader_${slot}_pairing_code_cipher")
            if (!validIp(host, false) || code.isBlank()) return null
            route = "/api/v1/telemetry"
            header = "X-Setup-Code"
        }
        val port = if (header == "X-Gateway-Token") ":18767" else ""
        val connection = URL("http://${host}${port}${route}")
            .openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 3500
            connection.requestMethod = "GET"
            connection.setRequestProperty(header, code)
            if (connection.responseCode != 200) return null
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (response.optString("source") != "jk02-ffe1-read-only") null else response
        } finally { connection.disconnect() }
    }

    private fun registeredMacs(slot: Int): List<String> = try {
        val raw = pref(slot, "paired_bms_json")
        val list = JSONArray(raw.ifBlank { "[]" })
        (0 until list.length()).mapNotNull {
            list.optString(it).takeIf { mac -> mac.isNotBlank() }
        }.distinct()
    } catch (_: Exception) { emptyList() }

    private fun validNumber(values: JSONObject, key: String): Double? {
        if (!values.has(key) || values.isNull(key)) return null
        return values.optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

    private fun parseBattery(
        slot: Int, record: JSONObject, defaultName: String
    ): JkBatteryReading? {
        val mac = record.optString("mac")
        if (mac.isBlank()) return null
        val values = record.optJSONObject("values")
        val fresh = record.optBoolean("available") && values != null
        val name = prefs.getString("bms_name_" + mac.replace(":", ""), "")
            ?.takeIf { it.isNotBlank() } ?: defaultName
        val socValue = values?.let { validNumber(it, "soc_pct") }
        val soc = socValue?.takeIf { it in 0.0..100.0 }?.toInt()
        val capacity = values?.let { validNumber(it, "nominal_capacity_ah") }
        val alarm: Boolean? = when {
            values == null -> null
            values.has("alarm_active") && !values.isNull("alarm_active") ->
                values.optBoolean("alarm_active")
            values.has("error_mask") && !values.isNull("error_mask") ->
                (values.optLong("error_mask") and (1L shl 4).inv()) != 0L
            else -> null
        }
        val cells = values?.optJSONArray("cells")?.let { list ->
            (0 until list.length()).mapNotNull { idx ->
                val cell = list.optJSONObject(idx) ?: return@mapNotNull null
                val voltage = validNumber(cell,"voltage_v") ?: return@mapNotNull null
                JkCellReading(cell.optInt("number",-1),voltage)
            }
        } ?: emptyList()
        val temps = values?.let { v ->
            temperatureNames.mapNotNull { (name,key) ->
                val value = validNumber(v,key) ?: return@mapNotNull null
                JkProbeReading(name,value)
            }
        } ?: emptyList()
        return JkBatteryReading(
            "$slot:$mac", name, soc, capacity, cells, temps, alarm, fresh)
    }

    private fun thresholds(): JkAlertThresholds = JkAlertThresholds(
        alarmEnabled=prefs.getBoolean(JkAlertPreferences.ALARM,true),
        globalSocEnabled=prefs.getBoolean(JkAlertPreferences.GLOBAL_SOC,false),
        globalSocBelow=prefs.getInt(JkAlertPreferences.GLOBAL_SOC_THRESHOLD,20).coerceIn(1,100),
        bmsSocEnabled=prefs.getBoolean(JkAlertPreferences.BMS_SOC,false),
        bmsSocBelow=prefs.getInt(JkAlertPreferences.BMS_SOC_THRESHOLD,20).coerceIn(1,100),
        cellHighEnabled=prefs.getBoolean(JkAlertPreferences.CELL_HIGH,false),
        cellAboveV=prefs.getFloat(JkAlertPreferences.CELL_HIGH_THRESHOLD,3.5f)
            .toDouble().coerceIn(2.0,4.5),
        tempHighEnabled=prefs.getBoolean(JkAlertPreferences.TEMP_HIGH,false),
        tempAboveC=prefs.getFloat(JkAlertPreferences.TEMP_HIGH_THRESHOLD,50.0f)
            .toDouble().coerceIn(0.0,120.0)
    )

    private fun checkThresholds() {
        if (!prefs.getBoolean(JkAlertPreferences.MONITOR,false)) return
        val vpn = vpnConnected()
        val configured = (1..3).associateWith { registeredMacs(it) }
        val expectedCount = configured.values.sumOf { it.size }
        // Stable numbering across readers, regardless of the selected settings tab.
        val names = mutableMapOf<String,Int>()
        var nextNumber = 1
        for (slot in 1..3)
            for (mac in configured[slot].orEmpty())
                names["$slot:$mac"] = nextNumber++
        val samples = mutableListOf<JkBatteryReading>()
        for (slot in 1..3) {
            if (Thread.currentThread().isInterrupted ||
                !prefs.getBoolean(JkAlertPreferences.MONITOR,false)) return
            if (configured[slot].isNullOrEmpty() && pref(slot,"device_id").isBlank()) continue
            val reply = try { read(slot,vpn) } catch (_: Exception) { null } ?: continue
            val records = reply.optJSONArray("devices") ?: continue
            val macs = configured[slot].orEmpty()
            for (index in 0 until records.length()) {
                val record=records.optJSONObject(index) ?: continue
                val mac=record.optString("mac")
                if(macs.isNotEmpty() && mac !in macs) continue
                val name="Batt-${names["$slot:$mac"] ?: (index+1)}"
                parseBattery(slot,record,name)?.let { samples += it }
            }
        }
        val options = thresholds()
        val result = JkAlertRules.evaluate(samples,expectedCount,options)
        val activeKeys = result.active.map { it.key }.toSet()
        // Clear only after a genuinely fresh measurement has returned to normal.
        // Unreachable readers cannot reset state and trigger false repeats.
        reported.removeAll { key ->
            val disabled=(key=="soc-global" && !options.globalSocEnabled) ||
                (key.startsWith("alarm:") && !options.alarmEnabled) ||
                (key.startsWith("soc:") && !options.bmsSocEnabled) ||
                (key.startsWith("cell:") && !options.cellHighEnabled) ||
                (key.startsWith("temp:") && !options.tempHighEnabled)
            disabled || (key in result.evaluatedKeys && key !in activeKeys)
        }
        for (event in result.active)
            if (event.key !in reported && notifyAlert(event))
                reported += event.key
    }

    private fun notifyAlert(event: JkAlertEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return false
        val notification = Notification.Builder(this, CHANNEL_ALARMS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(event.title)
            .setContentText(event.message)
            .setStyle(Notification.BigTextStyle().bigText(event.message))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(event.key, 3202, notification)
        return true
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        reported.clear()
        super.onDestroy()
    }
}
