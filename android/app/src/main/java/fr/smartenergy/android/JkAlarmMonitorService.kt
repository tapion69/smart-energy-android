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
 * No measurements or history are persisted; only alarm transitions notify.
 * Android may still stop the service; force-stop and lost connectivity prevent alerts.
 */
class JkAlarmMonitorService : Service() {
    companion object {
        const val ACTION_STOP = "fr.smartenergy.android.STOP_MONITOR"
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
        if (intent?.action == ACTION_STOP || !prefs.getBoolean("bms_alarm_notifications", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Start only after explicit user opt-in from the visible settings page.
        try {
            startForeground(SERVICE_ID, Notification.Builder(this, CHANNEL_RUNNING)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Smart Energy : surveillance active")
                .setContentText("Vérification des alarmes JK toutes les 30 secondes")
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
                try { checkAlarms() } catch (_: Exception) {
                    // A network or permission failure must not create a false alarm.
                }
            }, 0, 30, TimeUnit.SECONDS)
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
            if (preference == "gateway" || vpn) return null
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

    private fun checkAlarms() {
        val vpn = vpnConnected()
        for (slot in 1..3) {
            if (Thread.currentThread().isInterrupted ||
                !prefs.getBoolean("bms_alarm_notifications", false)) return
            // Skip a reader without pairing data; no network discovery in background.
            if (pref(slot, "device_id").isBlank()) continue
            val response = try { read(slot, vpn) } catch (_: Exception) { null }
                ?: continue
            val devices = response.optJSONArray("devices") ?: continue
            for (index in 0 until devices.length()) {
                val bms = devices.optJSONObject(index) ?: continue
                if (!bms.optBoolean("available")) continue
                val values = bms.optJSONObject("values") ?: continue
                val mac = bms.optString("mac")
                if (mac.isBlank()) continue
                val active: Boolean = when {
                    values.has("error_mask") && !values.isNull("error_mask") ->
                        values.optLong("error_mask", 0L) != 0L
                    values.has("alarm_active") -> values.optBoolean("alarm_active")
                    else -> continue
                }
                val identity = "${slot}:${mac}"
                if (active && reported.add(identity)) {
                    val custom = prefs.getString("bms_name_" + mac.replace(":", ""), "")
                        ?.takeIf { it.isNotBlank() }
                    notifyAlarm(identity, custom ?: "Batt-${index+1}")
                } else if (!active) reported.remove(identity)
            }
        }
    }

    private fun notifyAlarm(identity: String, name: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val notification = Notification.Builder(this, CHANNEL_ALARMS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Alarme JK BMS : ${name}")
            .setContentText("Une alarme est active. Ouvre Smart Energy pour consulter le détail.")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(identity, 3202, notification)
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        reported.clear()
        super.onDestroy()
    }
}
