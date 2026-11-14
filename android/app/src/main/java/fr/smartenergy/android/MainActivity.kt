package fr.smartenergy.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.location.LocationManager
import android.widget.RadioButton
import android.widget.RadioGroup
import android.view.View
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.net.Inet4Address
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Locale
import java.security.SecureRandom

/**
 * Smart Energy Android 0.3.0-dev (4 JK overview, pilot safe local writes).
 * Initial provisioning over ESP32's setup AP, subsequent management over home Wi-Fi.
 * Credentials kept encrypted in Android Keystore-backed local preferences.
 * HTTP only inside the trusted LAN or encrypted VPN subnet route; never a public IP.
 */
class MainActivity : Activity() {
    private val background = Color.rgb(10, 18, 29)
    private val panel = Color.rgb(24, 36, 54)
    private val normal = Color.rgb(239, 246, 255)
    private val subdued = Color.rgb(162, 176, 197)
    private val mint = Color.rgb(58, 212, 157)
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("smart_energy_v012", MODE_PRIVATE) }
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    @Volatile private var apNetwork: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var switchingToHome = false
    @Volatile private var activeSetupCode = ""
    @Volatile private var activeSetupSsid = ""
    @Volatile private var localHost = ""
    @Volatile private var lanVerified = false
    @Volatile private var remoteVpnConnection = false
    @Volatile private var remoteGatewayConnection = false
    @Volatile private var gatewayHost = ""
    @Volatile private var gatewaySecret = ""
    private lateinit var gatewayIpEdit: EditText
    private lateinit var gatewayTokenEdit: EditText
    @Volatile private var lanConnectionPending = false
    private var lastRemoteAttempt = 0L
    private var bootPairTicket = 0L
    private var bootPairCode = ""
    private var bootPairDeviceId = ""
    private lateinit var bootCommitBtn: Button
    private var lastLocalAttempt = 0L
    private lateinit var remoteStatus: TextView
    private lateinit var remoteCheck: CheckBox
    @Volatile private var setupTransport = false
    @Volatile private var lastLanFirmwareVersion = ""
    @Volatile private var selectedDeviceId = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var lanIp: EditText
    private lateinit var lanConnectBtn: Button
    private lateinit var physicalRecoverBtn: Button
    private lateinit var lanDiscoverBtn: Button
    private var discoverListener: NsdManager.DiscoveryListener? = null
    private val chosen = linkedSetOf<String>() // Uncommitted selection, UI thread only.
    private val registeredBms = linkedSetOf<String>() // Confirmed by ESP32 /api/v1/bms.
    private var lastScannedBms = emptyList<JkBmsRegistry.ScanItem>()
    private val scanChoices = mutableListOf<CheckBox>()

    private lateinit var status: TextView
    private lateinit var apName: EditText
    private lateinit var setupCode: EditText
    private lateinit var homeSsid: EditText
    private lateinit var homePassword: EditText
    private lateinit var scanPanel: LinearLayout
    private lateinit var telemetryPanel: LinearLayout
    private val dashboardCards = linkedMapOf<String, BatteryDashboardCard>()
    private val advancedCards = linkedMapOf<String, BatteryAdvancedCard>()
    private var dashboardMacs = emptyList<String>()
    private var currentTab = 0 // 0 Batterie, 1 Détails, 2 Paramètres
    private var parameterEditingEnabled = false // Never persisted; always locked on navigation.
    private var activeReaderSlot = 1
    private var pendingReaderTarget:Int?=null
    private lateinit var readerSelector: LinearLayout
    private lateinit var readerHeader: TextView
    private lateinit var pairingStatus: TextView
    private var selectedBmsMac = "" // empty = global overview if multiple BMS
    private var mountedBmsMac = ""
    private lateinit var bmsTabStrip: android.widget.HorizontalScrollView
    private lateinit var bmsTabRow: LinearLayout
    private lateinit var detailsTabRow: LinearLayout
    private var globalOverview: JkGlobalOverview? = null
    private var mountedTab = -1
    private var lastTelemetry: JSONObject? = null
    private var lastDetails: JSONObject? = null
    private var lastDetailsFetchedAt = 0L
    @Volatile private var detailsBusy = false
    private lateinit var tabOverview: Button
    private lateinit var tabExtended: Button
    private lateinit var tabSettings: Button
    private lateinit var telemetryStatus: TextView
    private lateinit var dashboardDebugStatus: TextView
    private var parameterWriteInFlight=false
    private var settingsRefreshBusy=false
    private var parameterWriteCooldownUntil=0L
    private lateinit var parameterFeedback: TextView
    private var lastParameterFeedback: String = ""
    @Volatile private var telemetryBusy = false
    private var debugEnabled=false
    private val fleetWorker=Executors.newSingleThreadExecutor()
    // Each reader exposes an HTTP snapshot already maintained by its BLE firmware.
    // Warm these snapshots in parallel; NO additional BLE scans or transactions.
    private val readerWarmWorker=Executors.newFixedThreadPool(3)
    private val warmReadersPending=mutableSetOf<Int>() // accessed only from UI
    private var lastWarmStartAt=0L
    private val settingsWorker=Executors.newSingleThreadExecutor()
    private val inverterWorker=Executors.newSingleThreadExecutor()
    // Low-frequency solar-counter reads must not delay live inverter telemetry.
    private val inverterEnergyWorker=Executors.newSingleThreadExecutor()
    // One shared native navigation regardless of inverter/JK availability.
    private var currentRootPage=0
    private var homeBatteryMode:Boolean?=null
    private var navigateNative:((Int)->Unit)?=null
    private lateinit var connectionBadge:TextView
    private lateinit var updateFeedback:TextView
    private lateinit var premiumDashboard: PremiumEnergyDashboard
    private lateinit var energyDashboard: EnergyDashboardV3
    // Lazy: no change to JK telemetry, associations, BMS cards or settings.
    private val voltronicFleet by lazy {
        VoltronicFleet(prefs,::auxiliaryCode,inverterWorker,inverterEnergyWorker,
            ::voltronicWifiNetwork,::voltronicVpnRoute) {
            publishPremiumDashboard()
        }
    }
    private val voltronicProfiles by lazy { VoltronicProfileStore(prefs) }
    private lateinit var voltronicProfileFeedback:TextView
    private lateinit var migrationFeedback:TextView
    // Bound to a physical Wi-Fi transport, not to an active Tailscale tunnel.
    // Voltronic is read-only and must never expose its setup code to a VPN proxy.
    private fun voltronicWifiNetwork():android.net.Network? =
        try {
            fun wifi(network:android.net.Network):Boolean =
                connectivity.getNetworkCapabilities(network)?.let { cap ->
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            // Prefer the currently active physical Wi-Fi to avoid selecting
            // a stale Network handle after an Android connectivity transition.
            connectivity.activeNetwork?.takeIf(::wifi)
                ?: connectivity.allNetworks.firstOrNull(::wifi)
        } catch (_:Exception) { null }
    private fun voltronicVpnRoute():VoltronicVpnRoute?=try {
        if(selectedRoute()!=JkTransportPolicy.Route.GATEWAY_VPN || !vpnConnected())
            null
        else {
            val gateway=prefs.getString("remote_gateway_ip","") ?: ""
            val token=storedGatewayToken()
            val vpn=connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(
                    NetworkCapabilities.TRANSPORT_VPN)==true
            }
            if(vpn==null || !VoltronicVpnReadOnlyClient.isTailnetIpv4(gateway) ||
                token.length !in 20..128)null
            else VoltronicVpnRoute(gateway,token,vpn)
        }
    } catch(_:Exception){null}

    // A saved JK reader or BMS is sufficient even if its measurements are stale.
    private fun hasAnyConfiguredJk():Boolean =
        (1..3).any { slot ->
            registeredMacs(slot).isNotEmpty() ||
            (if(slot==activeReaderSlot)
                prefs.getString("device_id","") else
                prefs.getString(slotKey(slot,"device_id"),"")).orEmpty().isNotBlank()
        }

    private var premiumJk = JSONObject()
    private var premiumInverter = JSONObject().put("configured",false)
    @Volatile private var voltronicReadBusy=false
    private var lastVoltronicReadAt=0L
    @Volatile private var fleetBusy=false
    private val fleetCache=mutableMapOf<Int,Pair<Long,JSONObject>>() // UI-thread only
    // Guards against a late HTTP reply from ESP1 being displayed after tapping ESP2.
    private var readerSelectionEpoch=0L
    private var nextFleetSlot=1
    private lateinit var fleetPanel:LinearLayout
    private lateinit var fleetOverview:JkGlobalOverview
    private var fleetConfiguredCount=0
    private var fleetAvailableCount=0
    private val fleetOwnerByMac=mutableMapOf<String,Int>()
    private val fleetNumberByMac=mutableMapOf<String,Int>()
    private var lastLocalSettingsRefreshAt=0L
    private var fleetTabSignature = ""
    private var pendingBmsNavigationMac = ""
    private val expandedSections = mutableMapOf<LinearLayout, MutableList<LinearLayout>>()
    private val expandedSectionHeaders = mutableMapOf<LinearLayout, MutableList<() -> Unit>>()
    private lateinit var alarmNotificationSwitch: CheckBox
    private lateinit var alarmSettingsState: TextView
    private val telemetryTicker = object : Runnable {
        override fun run() {
            // This tick runs on the UI thread. Watch Tailscale every 2.5 s,
            // including while a route is healthy: the former code only
            // connected automatically on startup and never returned from
            // the S3 VPN to the local reader without the manual button.
            val now=android.os.SystemClock.elapsedRealtime()
            if (!setupTransport && !lanConnectionPending) {
                val route=selectedRoute()
                if (lanVerified) {
                    when(route) {
                        JkTransportPolicy.Route.GATEWAY_VPN -> {
                            if (!remoteGatewayConnection) {
                                lastRemoteAttempt=now
                                connectGatewayRemote()
                            }
                        }
                        JkTransportPolicy.Route.LOCAL_WIFI -> {
                            if (remoteGatewayConnection || remoteVpnConnection) {
                                val ip=prefs.getString("local_ip","") ?: ""
                                if (allowedLocalIp(ip)) {
                                    lanIp.setText(ip)
                                    lastLocalAttempt=now
                                    connectHomeLan(false)
                                } else {
                                    lanVerified=false
                                    telemetryStatus.text="Wi-Fi local indisponible : lecteur non associé."
                                }
                            }
                        }
                        JkTransportPolicy.Route.CONFIGURE_VPN,
                        JkTransportPolicy.Route.NO_CONNECTION -> {
                            // Never silently use a saved LAN IP while a VPN
                            // is active but gateway credentials are missing.
                            lanVerified=false
                            if (route==JkTransportPolicy.Route.CONFIGURE_VPN)
                                telemetryStatus.text="VPN actif : configure « Accès extérieur — ESP32 VPN »."
                        }
                    }
                }
                if (!lanVerified && !lanConnectionPending) {
                    when(route) {
                        JkTransportPolicy.Route.GATEWAY_VPN -> {
                            if (now-lastRemoteAttempt>=30000L) {
                                lastRemoteAttempt=now
                                connectGatewayRemote()
                            }
                        }
                        JkTransportPolicy.Route.LOCAL_WIFI -> {
                            val savedIp=prefs.getString("local_ip","") ?: ""
                            if (now-lastLocalAttempt>=20000L) {
                                lastLocalAttempt=now
                                localHost=savedIp
                                lanIp.setText(savedIp)
                                connectHomeLan(false)
                            }
                        }
                        else -> { /* No unsafe LAN fallback while VPN is active. */ }
                    }
                }
            }
            if (lanVerified || (setupTransport && apNetwork != null)) {
                // Expired cached data must never stay visually "en ligne".
                val cached=fleetCache[activeReaderSlot]
                if(lastTelemetry!=null && cached!=null &&
                    !JkFastReaderPolicy.isFresh(
                        cached.first,android.os.SystemClock.elapsedRealtime())) {
                    showTelemetry(lastTelemetry!!)
                }
                refreshTelemetry()
                // The /details API returns a cache. Do not fetch it every 30 s.
                // The ESP32 itself now reads BMS settings every 30 min, plus
                // a one-shot fresh read on explicit edit/after each write.
                if(currentTab==2 &&
                   android.os.SystemClock.elapsedRealtime()-lastDetailsFetchedAt>1800000L)
                    refreshDetails()
                // BMS settings are not polled every minute just to keep
                // the editor armed. A single local read is requested on
                // unlock, explicitly via Refresh, and after each write.
            }
            pollOtherFleetReader()
            pollPremiumVoltronic(now)
            uiHandler.postDelayed(this, 2500)
        }
    }
    private lateinit var scanBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var wifiBtn: Button
    private lateinit var wifiStatus: TextView
    private lateinit var wifiNetworks: RadioGroup
    private lateinit var wifiScanBtn: Button
    private lateinit var accessCodeNew: EditText
    private lateinit var accessCodeBtn: Button
    private lateinit var autoEspBtn: Button
    private val wifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawUi()
        selectedDeviceId = prefs.getString("device_id", "") ?: ""
        val savedIp = prefs.getString("local_ip", "") ?: ""
        // Do not treat a missing VPN token/detection as permission to retry
        // the old local IP. This was the cause of false "local blocked by VPN".
        when(selectedRoute()) {
            JkTransportPolicy.Route.GATEWAY_VPN -> {
                lastRemoteAttempt=android.os.SystemClock.elapsedRealtime()
                connectGatewayRemote()
            }
            JkTransportPolicy.Route.LOCAL_WIFI -> {
                localHost=savedIp
                lanIp.setText(savedIp)
                lastLocalAttempt=android.os.SystemClock.elapsedRealtime()
                connectHomeLan(false)
            }
            JkTransportPolicy.Route.CONFIGURE_VPN ->
                state("VPN actif ou sélectionné : ouvre « Configuration et association » → « Accès extérieur — ESP32 VPN ». L'application ne basculera plus seule sur le Wi-Fi local.")
            JkTransportPolicy.Route.NO_CONNECTION ->
                if(storedCode().isNotBlank()) discoverHomeLan()
        }
        // Restore an explicitly enabled foreground monitoring service after
        // reopening the app, even when Android had previously reclaimed it.
        if(prefs.getBoolean("bms_alarm_notifications",false) &&
            (Build.VERSION.SDK_INT<33 ||
             checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED))
            startAlarmMonitoringService()
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val alias = "smart_energy_pairing_v1"
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun storedCode(): String {
        val encoded = prefs.getString("pairing_code_cipher", "") ?: ""
        if (encoded.isBlank()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 29) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
            String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Saved ciphertext is unreadable (e.g. Keystore reset); prompt for new pairing.
            ""
        }
    }

    private fun savePairing(deviceId: String, ssid: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val data = cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        prefs.edit()
            .putString("pairing_code_cipher", encoded)
            .putString("device_id", deviceId)
            .putString("setup_ssid", ssid)
            .apply()
        selectedDeviceId = deviceId
        activeSetupCode = secret
    }

    private fun allowedLocalIp(input: String): Boolean {
        // Development-only, IPv4 private ranges. Never send secret to public hosts.
        val parts = input.split(".")
        if (parts.size != 4 || parts.any { it.isBlank() || it.length > 3 || (it.toIntOrNull() ?: -1) !in 0..255 }) return false
        val p = parts.map { it.toInt() }
        if (p[0] == 10) return true
        if (p[0] == 192 && p[1] == 168) return true
        if (p[0] == 172 && p[1] in 16..31) return true
        return false
    }

    private fun vpnConnected(): Boolean {
        // Tailscale can be a separate VPN network while Android reports
        // Wi-Fi or cellular as activeNetwork. Check all visible VPN transports.
        return try {
            connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
            }
        } catch (_: Exception) {
            val active=connectivity.activeNetwork ?: return false
            connectivity.getNetworkCapabilities(active)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
        }
    }

    private fun selectedRoute(): JkTransportPolicy.Route {
        val vpn=vpnConnected()
        val preference=prefs.getString("connection_preference","auto") ?: "auto"
        val localReady=storedCode().isNotBlank() &&
            allowedLocalIp(prefs.getString("local_ip","") ?: "")
        val gatewayReady=allowedTailnetIp(prefs.getString("remote_gateway_ip","") ?: "") &&
            validGatewayToken(storedGatewayToken())
        return JkTransportPolicy.select(vpn,preference,
            prefs.getBoolean("remote_gateway_enabled",false),gatewayReady,localReady)
    }

    // Every installation brings its OWN Tailscale gateway; no maintainer server,
    // shared auth key, arbitrary public IP, or hidden user-specific config.
    private fun allowedTailnetIp(input: String): Boolean {
        val parts = input.split(".")
        if (parts.size != 4 || parts.any {
                it.isBlank() || it.length > 3 || (it.toIntOrNull() ?: -1) !in 0..255
            }) return false
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        return a == 100 && b in 64..127
    }

    private fun storedGatewayToken(): String {
        val encoded = prefs.getString("remote_gateway_token_cipher", "") ?: ""
        if (encoded.isBlank()) return ""
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            if (data.size < 29) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(),
                        GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun saveGatewayToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val data = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("remote_gateway_token_cipher",
            Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    private fun validGatewayToken(token: String): Boolean =
        // Gateway firmware accepts any strong ASCII header token without spaces
        // (length 20..128). Avoid rejecting otherwise valid local secrets.
        token.length in 20..128 && token.all { it.code in 33..126 }

    private fun supportsLanWifiUpdate(version: String): Boolean {
        val versionParts = version.substringBefore("-").split(".")
        if (versionParts.size != 3) return false
        val major = versionParts[0].toIntOrNull() ?: return false
        val minor = versionParts[1].toIntOrNull() ?: return false
        val patch = versionParts[2].toIntOrNull() ?: return false
        return major > 0 || minor > 1 || (minor == 1 && patch >= 3)
    }

    private fun d(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    private fun startAlarmMonitoringService() {
        val intent=Intent(this,JkAlarmMonitorService::class.java)
        if(Build.VERSION.SDK_INT>=26) startForegroundService(intent)
        else startService(intent)
    }

    private fun setAlarmMonitoring(enabled:Boolean) {
        if(enabled && Build.VERSION.SDK_INT>=33 &&
           checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            alarmNotificationSwitch.isChecked=false
            alarmSettingsState.text="Autorise les notifications Android, puis réactive la surveillance."
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),317)
            return
        }
        try {
            prefs.edit().putBoolean("bms_alarm_notifications",enabled).apply()
            if(enabled) startAlarmMonitoringService()
            else stopService(Intent(this,JkAlarmMonitorService::class.java))
            alarmSettingsState.text=if(enabled)
                "Surveillance activée · contrôle des seuils toutes les 30 secondes"
                else "Surveillance désactivée"
        } catch(ex:Exception) {
            prefs.edit().putBoolean("bms_alarm_notifications",false).apply()
            alarmNotificationSwitch.isChecked=false
            alarmSettingsState.text="Impossible de lancer la surveillance : ${ex.javaClass.simpleName}"
        }
    }

    private fun sendAlarmTestNotification() {
        if(Build.VERSION.SDK_INT>=33 &&
           checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            alarmSettingsState.text="Autorise les notifications Android pour recevoir l'alerte de test."
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),318)
            return
        }
        val manager=getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            JkAlarmMonitorService.CHANNEL_ALARMS,"Alarmes JK BMS",
            NotificationManager.IMPORTANCE_HIGH))
        manager.notify(3203,Notification.Builder(this,JkAlarmMonitorService.CHANNEL_ALARMS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Smart Energy : notification de test")
            .setContentText("Test réussi côté application. Aucune alarme BMS n'a été provoquée.")
            .setAutoCancel(true)
            .build())
        alarmSettingsState.text="Notification de test envoyée à Android."
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = d(14).toFloat()
    }

    private fun label(text: String, size: Float = 14f, bold: Boolean = false, color: Int = normal) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun input(hint: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(subdued)
        setTextColor(normal)
        textSize = 15f
        isSingleLine = true
        setPadding(d(13), d(10), d(13), d(10))
        background = rounded(Color.rgb(38, 54, 75))
        inputType = if (secret)
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        else android.text.InputType.TYPE_CLASS_TEXT
    }

    private fun section(root: LinearLayout, heading: String,
                        initiallyOpen: Boolean = heading.startsWith("Mes passerelles")): LinearLayout {
        val shell=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=rounded(panel)
            setPadding(d(15),d(12),d(15),d(12))
        }
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            visibility=if(initiallyOpen) View.VISIBLE else View.GONE
        }
        val header=label("",17f,true)
        fun refreshHeader() {
            header.text=(if(body.visibility==View.VISIBLE) "⌄  " else "›  ")+heading
            header.contentDescription=heading+
                if(body.visibility==View.VISIBLE) ", réduire" else ", développer"
        }
        header.setPadding(0,d(7),0,d(7))
        header.isClickable=true
        header.isFocusable=true
        header.setOnClickListener {
            val opening=body.visibility!=View.VISIBLE
            expandedSections[root]?.forEach { if(it!==body)it.visibility=View.GONE }
            body.visibility=if(opening)View.VISIBLE else View.GONE
            // Restore every header's arrow, including the ones just collapsed.
            expandedSectionHeaders[root]?.forEach { it() }
        }
        shell.addView(header)
        shell.addView(body)
        root.addView(shell,LinearLayout.LayoutParams(-1,-2).apply { topMargin=d(10) })
        expandedSections.getOrPut(root){ mutableListOf() }.add(body)
        expandedSectionHeaders.getOrPut(root){ mutableListOf() }.add { refreshHeader() }
        refreshHeader()
        return body
    }

    private fun add(p: LinearLayout, view: android.view.View, space: Int = 8) {
        p.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(space) })
    }

    private fun action(title: String, block: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        setTextColor(this@MainActivity.background)
        backgroundTintList = ColorStateList.valueOf(mint)
        setOnClickListener { block() }
    }

    private fun drawUi() {
        debugEnabled=prefs.getBoolean("debug_enabled",false)
        activeReaderSlot=prefs.getInt("active_reader_slot",1).coerceIn(1,3)
        val scroll = ScrollView(this).apply { setBackgroundColor(this@MainActivity.background) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(8), d(12), d(8), d(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Shared brand, route badge and main menu in native Android.
        // Both WebViews hide their own header/navigation: no duplicated menus.
        val brand = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(d(8),d(9),d(8),d(9))
            background=GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(9,43,59),Color.rgb(6,26,41))).apply {
                cornerRadius=d(13).toFloat()
            }
        }
        val brandLogo = ImageView(this).apply {
            setImageResource(R.drawable.smart_energy_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo Smart Energy"
        }
        brand.addView(brandLogo, LinearLayout.LayoutParams(d(42), d(42)))
        val branding=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val heading=label("SMART ENERGY",19f,true,Color.rgb(47,232,209)).apply {
            typeface=Typeface.create("sans-serif",Typeface.BOLD_ITALIC)
        }
        add(branding,heading,0)
        add(branding,label("Gestion Énergie Solaire & Batteries",10f,false,
            Color.rgb(171,205,218)),0)
        brand.addView(branding,LinearLayout.LayoutParams(0,-2,1f).apply {
            leftMargin=d(7)
        })
        connectionBadge=label("Hors ligne",10f,false,
            Color.rgb(161,235,214)).apply {
            gravity=Gravity.CENTER
            setPadding(d(6),d(7),d(6),d(7))
            background=GradientDrawable().apply {
                setColor(Color.rgb(17,60,72))
                setStroke(d(1),Color.rgb(49,123,147))
                cornerRadius=d(22).toFloat()
            }
        }
        brand.addView(connectionBadge,LinearLayout.LayoutParams(d(86),-2))
        add(root,brand,2)
        val homePage=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val dashboardPage=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val configurationPage=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val settingsPage=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val updatesPage=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val navButtons=linkedMapOf<Int,TextView>()
        fun showPage(page:Int) {
            currentRootPage=page
            val jkPresent=hasAnyConfiguredJk() || premiumJk.optInt("total",0)>0
            val batteryHome=EnergyDashboardSelection.showBatteryHome(
                voltronicFleet.hasInverters(),jkPresent)
            if(page==0)homeBatteryMode=batteryHome
            val energyVisible=(page==0 && !batteryHome) || page==4 || page==5
            val batteryVisible=(page==0 && batteryHome) || page==1
            homePage.visibility=if(energyVisible)View.VISIBLE else View.GONE
            dashboardPage.visibility=if(batteryVisible)View.VISIBLE else View.GONE
            configurationPage.visibility=if(page==2)View.VISIBLE else View.GONE
            settingsPage.visibility=if(page==3)View.VISIBLE else View.GONE
            updatesPage.visibility=if(page==6)View.VISIBLE else View.GONE
            // Never show the obsolete premium scene on a JK-only installation.
            if(::premiumDashboard.isInitialized)premiumDashboard.visibility=View.GONE
            if(::energyDashboard.isInitialized) {
                energyDashboard.visibility=if(energyVisible)View.VISIBLE else View.GONE
                energyDashboard.showSection(if(page==4)"inverters" else "home")
            }
            navButtons.forEach { (destination,button) ->
                val selected=destination==page
                button.setTextColor(if(selected)Color.rgb(2,43,49) else normal)
                button.background=GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    if(selected)
                        intArrayOf(Color.rgb(111,255,234),Color.rgb(21,201,176))
                    else
                        intArrayOf(Color.rgb(23,66,89),Color.rgb(9,34,55))).apply {
                    cornerRadius=d(11).toFloat()
                    setStroke(d(1),if(selected)
                        Color.rgb(168,255,243) else Color.rgb(64,134,162))
                }
            }
            parameterEditingEnabled=false
            mountedTab=-1
            if(batteryVisible)lastTelemetry?.let {showTelemetry(it)}
            scroll.post {scroll.scrollTo(0,0)}
        }
        navigateNative={ page -> showPage(page) }
        val navigation=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_VERTICAL
        }
        // Seven destinations in two rows: Config and MÀJ always visible.
        // Unlike a horizontal carousel, the menu cannot hide Configuration.
        // TextView (instead of platform Button) avoids forced min-width.
        val items=listOf(
            Triple("⌂ Accueil",0,"Accueil"),
            Triple("▣ Onduleurs",4,"Onduleurs"),
            Triple("▣ Batteries",1,"Batteries"),
            Triple("⇄ Énergie",5,"Énergie"),
            Triple("⚙ Config",2,"Configuration"),
            Triple("Réglages",3,"Réglages"),
            Triple("↻ MÀJ",6,"Mises à jour"))
        var navRow:LinearLayout?=null
        for((index,entry) in items.withIndex()) {
            val (title,page,description)=entry
            if(index%4==0) {
                navRow=LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                }
                navigation.addView(navRow,LinearLayout.LayoutParams(-1,d(49)))
            }
            val item=label(title,if(resources.configuration.screenWidthDp<375)9f else 10f,
                true,normal).apply {
                gravity=Gravity.CENTER
                isClickable=true
                isFocusable=true
                contentDescription=description
                setPadding(d(1),d(4),d(1),d(4))
                setOnClickListener {showPage(page)}
            }
            navButtons[page]=item
            navRow!!.addView(item,LinearLayout.LayoutParams(0,d(46),1f).apply {
                leftMargin=d(2);rightMargin=d(2)
            })
        }
        add(root,navigation,8)
        root.addView(homePage)
        root.addView(dashboardPage)
        root.addView(configurationPage)
        root.addView(settingsPage)
        root.addView(updatesPage)
        premiumDashboard=PremiumEnergyDashboard(this)
        premiumDashboard.onNavigate={ destination ->
            when(destination) {
                "section:inverters-card" -> showPage(4)
                "section:flows-card" -> showPage(5)
                "home" -> showPage(0)
                "batteries" -> showPage(1)
                "settings" -> showPage(3)
                "inverters" -> showPage(4)
                else -> showPage(0)
            }
        }
        // Retained for backward compatibility only: never display the old
        // house photo / obsolete four KPI cards on the JK-only home.
        premiumDashboard.visibility=View.GONE
        homePage.addView(premiumDashboard,LinearLayout.LayoutParams(-1,d(940)).apply {
            topMargin=d(7)
        })
        energyDashboard=EnergyDashboardV3(this)
        energyDashboard.visibility=View.GONE
        energyDashboard.onNavigate={ destination ->
            when(destination) {
                "batteries" -> showPage(1)
                "settings" -> showPage(3)
                "configuration" -> showPage(2)
                else -> {
                    if(destination.startsWith("voltronic-parameters:")) {
                        val slot=destination.substringAfter(':').toIntOrNull()
                        if(slot!=null && slot in 1..3 && voltronicFleet.configured(slot))
                            loadEditableVoltronicSettings(slot)
                    }else showPage(0)
                }
            }
        }
        energyDashboard.onStyleChanged={ style ->
            if(style=="modern" || style=="sunsynk") {
                prefs.edit().putString("energy_dashboard_style",style).apply()
                publishPremiumDashboard()
            }
        }
        homePage.addView(energyDashboard,LinearLayout.LayoutParams(-1,d(1100)).apply {
            topMargin=d(7)
        })
        // Initial page is selected after native JK controls are built.
        // No energy WebView is presented for a JK-only home.

        add(dashboardPage, label("Batteries JK · Détails et paramètres", 23f, true), 12)
        // Connection/setup feedback remains accessible in Configuration.
        // Its mirror on the dashboard is shown ONLY in Debug mode.
        status = label("Connexion à la passerelle…", 13f, false, subdued)
        add(configurationPage,status,10)
        dashboardDebugStatus=label("",13f,false,subdued)
        telemetryStatus = label("En attente des mesures réelles du JK", 13f, false, subdued)
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabOverview = action("Batterie") { switchDashboardTab(0) }
        tabExtended = action("Détails") { switchDashboardTab(1) }
        tabSettings = action("Paramètres") { switchDashboardTab(2) }
        for (button in listOf(tabOverview, tabExtended, tabSettings)) {
            button.textSize = 12f
            tabs.addView(button, LinearLayout.LayoutParams(0, d(52), 1f).apply {
                leftMargin = d(2); rightMargin = d(2)
            })
        }
        detailsTabRow = tabs
        bmsTabRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        bmsTabStrip = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bmsTabRow)
            visibility = View.GONE
        }
        add(dashboardPage, bmsTabStrip, 8)
        add(dashboardPage, detailsTabRow, 8)
        add(dashboardPage,dashboardDebugStatus,6)
        add(dashboardPage,telemetryStatus,8)
        refreshDebugVisibility()
        fleetPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fleetOverview=JkGlobalOverview(this)
        fleetPanel.addView(fleetOverview,LinearLayout.LayoutParams(-1,-2))
        add(dashboardPage,fleetPanel,10)
        parameterFeedback=label("",12f,false,mint).apply { visibility=View.GONE }
        add(dashboardPage, parameterFeedback, 6)
        highlightActiveTab()
        telemetryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(dashboardPage, telemetryPanel, 8)
        add(dashboardPage, action("⟳ Actualiser les mesures") { refreshTelemetry() }, 8)
        add(dashboardPage, label("Mesures expérimentales. Les réglages du BMS ne peuvent être modifiés qu'après un déverrouillage explicite.", 12f, color = subdued), 8)
        val advanced=configurationPage
        add(advanced,label("Configuration et associations",25f,true),18)
        // All JK settings live in one group with one ESP 1/2/3 selector.
        // Existing pairings, per-reader preferences and BMS registry are preserved.
        val jkConfiguration=section(advanced,"Batteries JK · ESP 1 / ESP 2 / ESP 3",true)
        val readers=jkConfiguration
        add(readers,label("Sélectionne ESP 1, ESP 2 ou ESP 3. L'adresse, le code et les BMS enregistrés restent propres à chaque lecteur.",12f,color=subdued))
        readerSelector=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        add(readers,readerSelector,6)
        readerHeader=label("",13f,false,normal)
        add(readers,readerHeader,8)
        refreshReaderSelector()

        val lan = section(jkConfiguration, "Connexion quotidienne · IP et code ESP32 JK",true)
        add(lan, label("Pour un lecteur déjà préparé dans le flasher, saisis son IP et son code, puis connecte-le. L'association BOOT n'est pas nécessaire dans ce cas.",12f,color=subdued))
        lanIp = input("Adresse IP locale de l'ESP32 (ex. 192.168.1.50)")
        lanIp.setText(prefs.getString("local_ip", "") ?: "")
        add(lan, lanIp)
        setupCode = input("Code ESP32 JK (vide s'il est déjà mémorisé)",true)
        add(lan,setupCode,6)
        lanConnectBtn = action("Se connecter à ma passerelle") {
            // Manual local is an explicit override for JK setup/writes.
            remoteCheck.isChecked=false
            prefs.edit().putString("connection_preference","local")
                .putBoolean("remote_gateway_enabled",false).apply()
            connectHomeLan()
        }
        add(lan, lanConnectBtn)
        lanDiscoverBtn = action("Rechercher sur mon Wi-Fi") { discoverHomeLan() }
        add(lan, lanDiscoverBtn)
        add(lan, label("Le code est chiffré dans l'application après une connexion réussie. Pour le remplacer sur l'ESP32, utilise l'option ci-dessous.",12f,color=subdued))

        val access = section(lan, "Changer le code ESP32 JK · optionnel")
        add(access, label("Après connexion locale, définis un nouveau code de 8 à 32 caractères. L'application sauvegarde le changement pour l'ESP sélectionné.", 13f, color = subdued))
        accessCodeNew = input("Nouveau code ESP32 (8 à 32 caractères)", true)
        add(access, accessCodeNew)
        accessCodeBtn = action("Changer le code de ma passerelle") { changeDeviceCode() }
        add(access, accessCodeBtn)

        val b = section(jkConfiguration, "Gérer les BMS enregistrés · lecteur sélectionné")
        add(b, label("Chaque lecteur accepte deux BMS dans l'application. Décoche un BMS puis sauvegarde pour le retirer ; son adresse reste connue pour le réassocier. Utilise Renommer pour personnaliser chaque batterie.",13f,color=subdued))
        add(b, action("Afficher mes BMS enregistrés") { loadSavedBms() }, 8)
        scanBtn = action("Rechercher de nouveaux appareils Bluetooth") { scanBle() }
        add(b, scanBtn)
        scanPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(b, scanPanel)
        saveBtn = action("Sauvegarder les batteries choisies") { saveSelection() }
        add(b, saveBtn)
        add(b, label("Une case décochée ne prend effet qu'après « Sauvegarder ». La suppression retire seulement l'adresse de la liste ESP32 et termine cette connexion BLE ; les paramètres physiques du BMS ne sont jamais effacés. Les anciennes MAC restent connues de cette application pour être réactivées.",12f,color=subdued))

        // Keep the pairing controls together, immediately under the reader
        // selector. Both the scan and BOOT pairing act on the chosen slot.
        val assistant=section(jkConfiguration,"Association BOOT · nouveau lecteur ou récupération")
        add(assistant,label("Réservé aux lecteurs non encore provisionnés ou à un accès oublié. Un lecteur déjà configuré dans le flasher se connecte directement par IP + code ci-dessus.",12f,color=subdued))
        autoEspBtn=action("1 · Rechercher mon ESP32") { findNearbyEsp32() }
        add(assistant,autoEspBtn,8)
        apName=input("SmartJK-Setup-XXXXXX (réseau du lecteur choisi)")
        apName.setText(prefs.getString("setup_ssid","") ?: "")
        add(assistant,apName,6)
        pairingStatus=label("ESP $activeReaderSlot sélectionné. "+
            "Recherche son réseau SmartJK-Setup, puis associe-le avec BOOT.",13f,false,mint)
        add(assistant,pairingStatus,8)
        add(assistant,label("2. Ouvre d'abord la fenêtre d'association et "+
            "remplis le code personnel et le Wi-Fi. Juste avant de te connecter, "+
            "maintiens BOOT 4 secondes sur cet ESP32 déjà allumé, puis relâche. "+
            "La connexion temporaire reste ouverte 2 minutes.",12f,color=subdued))
        physicalRecoverBtn=action("2 · Associer ESP $activeReaderSlot avec BOOT") {
            beginBootPairing()
        }
        add(assistant,physicalRecoverBtn,8)
        add(assistant,label("3. Après avoir saisi ton code dans la fenêtre "+
            "d'association et accepté la connexion Android, attends le message "+
            "« Code prêt ». Maintiens ensuite BOOT 2 secondes sur le même ESP32, "+
            "relâche, puis confirme dans l'application.",12f,color=subdued))
        bootCommitBtn=action("3 · Après le 2e BOOT : confirmer l'association") {
            commitBootPairing()
        }
        add(assistant,bootCommitBtn,8)

        val remote = section(jkConfiguration, "Accès extérieur · ESP32 VPN / Tailscale")
        add(remote, label("Notre deuxième ESP32 relaie les lectures JK via le VPN Tailscale. Aucun mini-PC, aucun serveur Smart Energy et aucun port à ouvrir sur la box.", 13f, color = subdued))
        remoteStatus = label(if (vpnConnected()) "Tailscale / VPN Android détecté" else
            "VPN Android inactif : active Tailscale sur ton téléphone.", 12f, color = subdued)
        add(remote, remoteStatus, 8)
        gatewayIpEdit = input("IP Tailscale de l'ESP32 VPN (ex. 100.64.0.1)")
        gatewayIpEdit.setText(prefs.getString("remote_gateway_ip", "") ?: "")
        add(remote, gatewayIpEdit)
        gatewayTokenEdit = input("Token VPN du fichier de configuration (20 caractères minimum)", true)
        gatewayTokenEdit.hint = if (storedGatewayToken().isNotBlank())
            "Token VPN déjà mémorisé — laisser vide" else "Valeur CONFIG_SE_GATEWAY_TOKEN (sans guillemets)"
        add(remote, gatewayTokenEdit)
        add(remote, label("Saisis CONFIG_SE_GATEWAY_TOKEN, pas ton code JK et pas la clé tskey-auth Tailscale. Le code JK choisi dans l'application doit correspondre à CONFIG_SE_JK_READ_CODE compilé dans l'ESP32 VPN. Aucun port à ouvrir.", 12f, color = subdued))
        add(remote, action("Enregistrer et tester mon ESP32 VPN") {
            // After successful setup, prefer the S3 whenever Tailscale runs.
            // Returning to home Wi-Fi without Tailscale is automatic.
            remoteCheck.isChecked=true
            prefs.edit().putString("connection_preference","auto")
                .putBoolean("remote_gateway_enabled",true).apply()
            connectGatewayRemote()
        }, 10)
        remoteCheck = CheckBox(this).apply {
            text = "Automatique : Tailscale actif → VPN, sinon → Wi-Fi local"
            setTextColor(normal)
            isChecked = prefs.getBoolean("remote_gateway_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean("remote_gateway_enabled", checked)
                    .putString("connection_preference",
                        if (checked) "auto"
                        else if (remoteGatewayConnection) "gateway" else "local")
                    .apply()
                remoteStatus.text = if (checked)
                    "Mode automatique actif : VPN avec Tailscale, Wi-Fi local sans Tailscale."
                    else "Mode automatique désactivé : sélection manuelle conservée."
            }
        }
        add(remote, remoteCheck)
        add(remote, label("Une fois le VPN associé, la connexion revient seule aux ESP32 locaux quand Tailscale est coupé. Pour associer un JK ou modifier ses réglages, désactive le mode automatique et utilise le Wi-Fi local. Le VPN reste en lecture seule.", 12f, color = subdued))
        add(remote, action("Revenir à ma connexion Wi-Fi locale") {
            remoteCheck.isChecked=false
            prefs.edit().putString("connection_preference","local")
                .putBoolean("remote_gateway_enabled",false).apply()
            connectHomeLan(false)
        })
        add(remote, label("Option existante : si tu utilises un routeur VPN de sous-réseau plutôt qu'un deuxième ESP32, l'ancien mode reste disponible ci-dessous.", 12f, color = subdued))
        add(remote, action("Tester l'ancien mode VPN de sous-réseau") {
            if (!vpnConnected()) {
                state("Active ton VPN donnant accès au LAN avant ce test.")
            } else {
                connectHomeLan(true)
            }
        }, 8)

        val a = section(jkConfiguration, "Dépannage · anciens lecteurs JK")

        add(a, label("Uniquement pour un ancien lecteur encore en point d'accès SmartJK-Setup. Le code actuel se saisit dans Connexion quotidienne, à côté de l'IP.",color=subdued))
        add(a, action("Connecter l'ESP32") { connectSetupAp() }, 12)
        add(a, label("Avec le mode BOOT 0.3.1, l'application rejoint automatiquement le Wi-Fi temporaire et demande une seconde confirmation physique ; il reste ouvert au maximum 2 minutes.", 12f, color = subdued))

        val c = section(jkConfiguration, "Dépannage · modifier le Wi-Fi 2,4 GHz")
        wifiScanBtn = action("Rechercher les réseaux Wi-Fi disponibles") { scanRouterWifi() }
        add(c, wifiScanBtn)
        wifiNetworks = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        add(c, wifiNetworks)
        add(c, label("Choisis un réseau dans la liste. Seuls les réseaux 2,4 GHz accessibles à l'ESP32 sont proposés.", 12f, color = subdued))
        homeSsid = input("Nom de ton réseau Wi-Fi (SSID)")
        homePassword = input("Mot de passe Wi-Fi", true)
        homeSsid.visibility = View.GONE
        add(c, homeSsid)
        add(c, action("Saisir un nom de réseau manuellement") {
            homeSsid.visibility = if (homeSsid.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        add(c, homePassword)
        wifiBtn = action("Enregistrer / modifier le Wi-Fi") { sendHomeWifi() }
        add(c, wifiBtn)
        wifiStatus = label("Si ton ESP32 est déjà connecté à ta box, inutile de remplir cette section. Ce bouton sert à changer le réseau Wi-Fi.", 13f, color = subdued)
        add(c, wifiStatus)
        add(c, label("À utiliser seulement en cas de changement de box, SSID ou mot de passe. La première association BOOT configure déjà le Wi-Fi. Réseau local de confiance uniquement ; ne jamais exposer l'API ESP32 sur Internet.", 12f, color = subdued))

        val diagnosticSection=section(jkConfiguration,"Diagnostics avancés JK")
        val diagnosticSwitch=CheckBox(this).apply {
            text="Afficher les diagnostics avancés (BLE, radio, Wi-Fi, mémoire)"
            setTextColor(normal)
            isChecked=debugEnabled
            setOnCheckedChangeListener { _,checked ->
                debugEnabled=checked
                prefs.edit().putBoolean("debug_enabled",checked).apply()
                refreshDebugVisibility()
                mountedTab=-1 // Recreate details so debug-only rows also disappear.
                lastTelemetry?.let { showTelemetry(it) }
            }
        }
        add(diagnosticSection,diagnosticSwitch)
        add(diagnosticSection,label("Désactivé par défaut. Les alertes réelles du BMS restent visibles sur les cartes.",12f,color=subdued))
        renderFleetOverview()

        // First inverter interoperability test: authenticated Voltronic GET or
        // SunSpec Modbus/TCP FC03 discovery, never changes inverter settings.
        val inverter=section(advanced,"Onduleurs Voltronic / SunSpec — lecture seule")
        add(inverter,label("Détection expérimentale sur le Wi-Fi local. "+
            "Voltronic nécessite un ESP32-S3 équipé d'une interface RS232 "+
            "adaptée ; SunSpec lit directement les modèles Modbus TCP d'un "+
            "onduleur qui prend en charge cette norme. Aucun réglage n'est écrit.",
            12f,color=subdued))
        val inverterTypes=RadioGroup(this).apply { orientation=RadioGroup.VERTICAL }
        val inverterVolt=RadioButton(this).apply {
            text="Voltronic / Axpert — ESP32-S3 RS232"
            setTextColor(normal)
        }
        val inverterSun=RadioButton(this).apply {
            text="SunSpec — Modbus TCP (FC03 uniquement)"
            setTextColor(normal)
        }
        inverterTypes.addView(inverterVolt)
        inverterTypes.addView(inverterSun)
        inverterVolt.isChecked=prefs.getString("inverter_probe_type","voltronic")!="sunspec"
        inverterSun.isChecked=!inverterVolt.isChecked
        add(inverter,inverterTypes,5)
        val inverterIp=input("Adresse IP locale du lecteur / onduleur")
        inverterIp.setText(prefs.getString("inverter_probe_ip","") ?: "")
        add(inverter,inverterIp,6)
        val inverterPort=input("Port TCP : 18081 (Voltronic) ou 502 (SunSpec)")
        inverterPort.inputType=android.text.InputType.TYPE_CLASS_NUMBER
        inverterPort.setText(prefs.getInt("inverter_probe_port",18081).toString())
        inverterVolt.setOnClickListener { inverterPort.setText("18081") }
        inverterSun.setOnClickListener { inverterPort.setText("502") }
        add(inverter,inverterPort,6)
        val inverterCode=input("Code de lecture ESP32 Voltronic (8–32 caractères)",true)
        add(inverter,inverterCode,6)
        val inverterUnit=input("Adresse esclave SunSpec (1 par défaut)")
        inverterUnit.inputType=android.text.InputType.TYPE_CLASS_NUMBER
        inverterUnit.setText("1")
        add(inverter,inverterUnit,6)
        val inverterStatus=label("Aucune connexion testée.",12f,false,subdued)
        add(inverter,inverterStatus,6)
        add(inverter,action("Tester mon onduleur en lecture seule") {
            val ip=inverterIp.text.toString().trim()
            val type=if(inverterSun.isChecked)"sunspec" else "voltronic"
            val port=inverterPort.text.toString().trim().toIntOrNull()
            val unit=inverterUnit.text.toString().trim().toIntOrNull()
            val code=inverterCode.text.toString()
            if(!SunSpecReadOnly.isPrivateIPv4(ip) || port==null ||
                port !in 1..65535 || (type=="voltronic" && code.length !in 8..32) ||
                (type=="sunspec" && (unit==null || unit !in 0..247))) {
                inverterStatus.text="Vérifie l'IP privée, le port, le code Voltronic "+
                    "ou l'adresse esclave SunSpec."
            }else {
                prefs.edit().putString("inverter_probe_ip",ip)
                    .putString("inverter_probe_type",type)
                    .putInt("inverter_probe_port",port)
                    .putBoolean("inverter_probe_validated",false).apply()
                inverterStatus.text="Lecture réseau en cours (aucune écriture)…"
                inverterWorker.execute {
                    val result=try {
                        if(type=="sunspec"){
                            val found=SunSpecReadOnly.discover(ip,port,unit!!)
                            prefs.edit().putBoolean("inverter_probe_validated",true).apply()
                            "SunSpec détecté : base ${found.baseRegister}, "+
                                "${found.models.size} modèle(s) (IDs : "+
                                found.models.joinToString(",") { it.modelId.toString() }+")."
                        }else{
                            val wifi=voltronicWifiNetwork()
                                ?: throw IllegalStateException("Connecte le téléphone au Wi-Fi local de l'ESP32-S3")
                            val found=VoltronicReadOnlyClient.probe(ip,code,port,wifi)
                            saveAuxiliaryCode("premium_voltronic_code_cipher",code)
                            prefs.edit().putBoolean("inverter_probe_validated",true).apply()
                            "Lecteur Voltronic reconnu : "+
                                found.optString("version","version inconnue")+
                                " · mode lecture seule confirmé."
                        }
                    }catch(ex:Exception){
                        "Aucune lecture compatible : "+
                            (ex.message?.take(120) ?: ex.javaClass.simpleName)
                    }
                    runOnUiThread {
                        inverterStatus.text=result
                        lastVoltronicReadAt=0L
                        pollPremiumVoltronic(android.os.SystemClock.elapsedRealtime())
                    }
                }
            }
        },8)
        val voltVpn=section(advanced,"Accès VPN Voltronic · ESP32 Tailscale")
        add(voltVpn,label("Depuis Internet : Tailscale Android vers l'ESP32 VPN, "+
            "puis uniquement les lectures de INV1/INV2/INV3 sur le réseau local. "+
            "L'ESP32 VPN doit être configuré dans le flasher avec les IP et "+
            "codes Voltronic. Aucune écriture n'est relayée.",12f,color=subdued))
        add(voltVpn,label("Automatique : quand l'accès extérieur ESP32 VPN "+
            "est actif pour JK, les mesures Voltronic empruntent la même "+
            "passerelle en lecture seule. Aucune case d'activation séparée.",12f,color=subdued))
        add(voltVpn,label("Lorsque le VPN JK est sélectionné, l'application utilise "+
            "automatiquement la même adresse Tailscale et le même token pour "+
            "les lectures des onduleurs. Attention : le firmware de l'ESP VPN "+
            "doit aussi posséder les routes Voltronic et les IP/codes INV "+
            "provisionnés par USB. Une erreur 503 signifie configuration "+
            "Voltronic absente sur la passerelle, 404 firmware VPN trop ancien, "+
            "502 lecteur Voltronic inaccessible. Aucune modification ni OTA "+
            "n'est relayée par le VPN.",12f,color=subdued))
        add(voltVpn,action("Tester le relais VPN Voltronic INV1") {
            val gateway=voltronicVpnRoute()
            if(gateway==null)state("Active Tailscale, configure le token et coche VPN Voltronic.")
            else inverterWorker.execute {
                val result=try {
                    val obj=VoltronicVpnReadOnlyClient.read(1,false,gateway)
                    if(obj.optBoolean("available"))
                        "INV1 : télémétrie Voltronic reçue par VPN."
                    else "INV1 : relais joint, mesure QPIGS indisponible."
                }catch(ex:VoltronicVpnHttpException){
                    "VPN INV1 : "+ex.userHint
                }catch(ex:Exception){
                    "VPN INV1 : échec réseau ou délai expiré ("+
                        ex.javaClass.simpleName.take(45)+")"
                }
                runOnUiThread { state(result) }
            }
        })
        // Independent Voltronic slots INV2/INV3: never modify JK configuration.
        for(slot in 2..3) {
            val panel=section(advanced,"Onduleur INV$slot · Voltronic (lecture seule)")
            add(panel,label("ESP32-S3 Voltronic indépendant. Le code est conservé "+
                "avec la protection Android Keystore.",12f,color=subdued))
            val ip=input("Adresse IP privée INV$slot")
            ip.setText(prefs.getString("inverter_${slot}_ip","") ?: "")
            add(panel,ip,6)
            val port=input("Port HTTP · 18081")
            port.inputType=android.text.InputType.TYPE_CLASS_NUMBER
            port.setText(prefs.getInt("inverter_${slot}_port",18081).toString())
            add(panel,port,6)
            val code=input("Code ESP32-S3 (8 à 32 caractères)",true)
            add(panel,code,6)
            val feedback=label(
                if(voltronicFleet.configured(slot)) "INV$slot : enregistré"
                else "INV$slot : non configuré",12f,false,subdued)
            add(panel,feedback,6)
            add(panel,action("Tester et enregistrer INV$slot") {
                val host=ip.text.toString().trim()
                val tcpPort=port.text.toString().trim().toIntOrNull()
                val secret=code.text.toString()
                if(!SunSpecReadOnly.isPrivateIPv4(host) || tcpPort==null ||
                   tcpPort !in 1..65535 || secret.length !in 8..32) {
                    feedback.text="Vérifie IP privée, port et code de lecture."
                } else {
                    feedback.text="Lecture de contrôle en cours…"
                    inverterWorker.execute {
                        val response=try {
                            val wifi=voltronicWifiNetwork()
                                ?: throw IllegalStateException("Wi-Fi local requis pour INV$slot")
                            VoltronicReadOnlyClient.probe(host,secret,tcpPort,wifi)
                            saveAuxiliaryCode("premium_voltronic_${slot}_code_cipher",secret)
                            prefs.edit().putString("inverter_${slot}_ip",host)
                                .putInt("inverter_${slot}_port",tcpPort)
                                .putBoolean("inverter_${slot}_validated",true).apply()
                            "INV$slot : lecture seule vérifiée, équipement enregistré"
                        } catch(ex:Exception) {
                            "INV$slot : " + (ex.message?.take(110) ?: "connexion impossible")
                        }
                        runOnUiThread {
                            feedback.text=response
                            voltronicFleet.changed()
                        }
                    }
                }
            },8)
            add(panel,action("Retirer INV$slot") {
                AlertDialog.Builder(this).setTitle("Retirer INV$slot ?")
                    .setMessage("Cette action retire uniquement cet onduleur de la "+
                        "configuration. Les JK et leurs associations restent inchangés.")
                    .setNegativeButton("Annuler",null)
                    .setPositiveButton("Retirer") { _,_ ->
                        prefs.edit().putBoolean("inverter_${slot}_validated",false)
                            .remove("inverter_${slot}_ip")
                            .remove("inverter_${slot}_port")
                            .remove("premium_voltronic_${slot}_code_cipher").apply()
                        feedback.text="INV$slot : non configuré"
                        voltronicFleet.changed()
                    }.show()
            },6)
        }
        add(inverter,label("Après association, les mesures Voltronic s'affichent "+
            "sur Accueil. Les mesures absentes restent indisponibles, sans valeur "+
            "fictive.",12f,color=subdued))
        add(advanced, label("BLE multi-batteries, OTA et accès distant : fonctions en développement.", 12f, color = subdued), 12)

        add(updatesPage,label("Mises à jour Smart Energy",23f,true),12)
        add(updatesPage,label("Même principe que WaterVan : publication GitHub, "+
            "notification de version et téléchargement volontaire. "+
            "Les mises à jour de l'application préservent les réglages "+
            "uniquement si le nom du paquet et le certificat de signature "+
            "restent identiques.",12f,color=subdued))
        val appUpdates=section(updatesPage,"APK Android · versions GitHub",true)
        add(appUpdates,label("Une version plus récente sera proposée si "+
            "un manifeste officiel et une APK signée sont publiés sur GitHub. "+
            "La mise à jour s'effectue par-dessus l'application existante, "+
            "sans désinstallation, si la signature correspond.",12f,color=subdued))
        add(appUpdates,action("Vérifier les mises à jour Android") {
            checkSmartEnergyUpdates(true)
        })
        add(appUpdates,action("Terminer l'installation de l'APK vérifiée") {
            try {
                val started=SmartEnergyApkInstaller.openAndroidInstaller(this)
                if(!started)updateFeedback.text="Autorise l'installation pour Smart Energy dans les réglages Android, puis reviens ici pour terminer."
            }catch(ex:Exception){
                updateFeedback.text="Installation indisponible : "+(ex.message?:ex.javaClass.simpleName)
            }
        },6)
        val firmwareUpdates=section(updatesPage,"Firmware ESP32 Voltronic · mises à jour",true)
        add(firmwareUpdates,label("Vérification de la version de chaque ESP32-S3 "+
            "en Wi-Fi local. Si un firmware officiel plus récent existe, "+
            "l'application propose l'OTA avec vérification SHA-256 et confirmation. "+
            "Le test 0.3.2 → 0.3.4 intégré est proposé uniquement à la version "+
            "de test correspondante. Aucune écriture automatique.",12f,color=subdued))
        add(firmwareUpdates,action("Vérifier les MAJ Voltronic") {
            val slots=(1..3).filter {voltronicFleet.configured(it)}
            if(slots.isEmpty())updateFeedback.text="Configure d'abord un ESP32-S3 Voltronic."
            else AlertDialog.Builder(this)
                .setTitle("Vérifier la mise à jour de quel onduleur ?")
                .setItems(slots.map{"INV$it"}.toTypedArray()) { _,which ->
                    checkVoltronicOtaForSlot(slots[which])
                }.setNegativeButton("Annuler",null).show()
        },7)
        val jkUpdates=section(updatesPage,"Firmware ESP32 JK · mises à jour",true)
        add(jkUpdates,label("Vérification des lecteurs ESP1 / ESP2 / ESP3 "+
            "sans interrompre le Bluetooth. Une OTA depuis GitHub sera "+
            "proposée seulement si le firmware du lecteur annonce une "+
            "partition OTA prête et qu'une image plus récente avec "+
            "SHA-256 est publiée. Les firmwares JK historiques ota=false "+
            "exigent une première migration USB sans effacement NVS.",12f,color=subdued))
        add(jkUpdates,action("Vérifier les MAJ des ESP JK") {
            checkJkFirmwareUpdates()
        },7)
        updateFeedback=label("Aucune vérification effectuée.",13f,false,subdued)
        add(updatesPage,updateFeedback,8)
        add(updatesPage,action("Voir les publications Smart Energy sur GitHub") {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com/tapion69/smart-energy-android/releases")))
        },6)

        add(settingsPage,label("Réglages de l'application",25f,true),16)
        val migration=section(settingsPage,"Sauvegarde / migration de configuration",true)
        add(migration,label("Une mise à jour Android signée avec le même certificat "+
            "conserve automatiquement toutes les données, y compris les codes "+
            "chiffrés. Une ancienne APK signée différemment ne peut pas être "+
            "remplacée directement : importer ici la sauvegarde non secrète "+
            "créée depuis l'ancienne APK sur le PC. Les codes ESP32 et le "+
            "token VPN devront être saisis une seule fois après migration.",
            12f,color=subdued))
        add(migration,action("Exporter ma configuration non secrète en JSON") {
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/json"
                    putExtra(Intent.EXTRA_TITLE,"SmartEnergy_Configuration.json")
                },713)
            }catch(ex:Exception) {
                migrationFeedback.text="Export indisponible : "+ex.javaClass.simpleName
            }
        },8)
        add(migration,action("Importer une configuration depuis l'ancienne APK") {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/json"
                },714)
            }catch(ex:Exception) {
                migrationFeedback.text="Import indisponible : "+ex.javaClass.simpleName
            }
        },8)
        migrationFeedback=label("Aucune configuration importée. L'APK existante "+
            "doit rester installée jusqu'à validation de la migration.",
            12f,false,subdued)
        add(migration,migrationFeedback,8)
        val voltProfiles=section(settingsPage,
            "Profils Voltronic · apprentissage et sauvegarde",true)
        add(voltProfiles,label("L'application apprend les capacités et les "+
            "paramètres QPIRI/QDOP réellement lus pour INV1 à INV3. "+
            "Les profils restent sur ce téléphone et ne modifient "+
            "jamais un paramètre de l'onduleur.",12f,color=subdued))
        add(voltProfiles,action("Exporter mes profils Voltronic en JSON") {
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/json"
                    putExtra(Intent.EXTRA_TITLE,
                        "smart-energy-voltronic-profils.json")
                },711)
            } catch(ex:Exception) {
                voltronicProfileFeedback.text="Export impossible : "+
                    ex.javaClass.simpleName
            }
        },8)
        add(voltProfiles,action("Importer un profil JSON (sans application)") {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type="application/json"
                },712)
            } catch(ex:Exception) {
                voltronicProfileFeedback.text="Import impossible : "+
                    ex.javaClass.simpleName
            }
        },6)
        voltronicProfileFeedback=label(
            "Profils liés aux modèles détectés. Export sans IP, codes, "+
            "numéros de série ni données Wi-Fi.",12f,false,subdued)
        add(voltProfiles,voltronicProfileFeedback,8)
        add(voltProfiles,label(
            "Les paramètres restaurés servent uniquement de référence. "+
            "Aucune écriture automatique. Les commandes ne seront apprises "+
            "qu'après vérification d'une vraie relecture QPIRI/QDOP.",12f,color=subdued))
        val energyStyle=section(settingsPage,"Affichage du dashboard énergie")
        add(energyStyle,label("Le tableau de bord JK seul reste strictement identique "+
            "à celui de la version précédente.",12f,color=subdued))
        add(energyStyle,action("Vue Moderne / Premium") {
            prefs.edit().putString("energy_dashboard_style","modern").apply()
            publishPremiumDashboard()
        },6)
        add(energyStyle,action("Vue Sunsynk") {
            prefs.edit().putString("energy_dashboard_style","sunsynk").apply()
            publishPremiumDashboard()
        },6)
        val alerts=section(settingsPage,"Notifications et seuils JK",true)
        add(alerts,label(
            "Surveillance de tous les BMS configurés sur ESP 1, 2 et 3, "+
            "même lorsque Smart Energy n'est plus à l'écran. Vérification "+
            "toutes les 30 secondes, sans enregistrer d'historique sur "+
            "le téléphone ou les ESP32.",13f,color=subdued))
        alarmNotificationSwitch=CheckBox(this).apply {
            text="Activer la surveillance en arrière-plan"
            setTextColor(normal)
            isChecked=prefs.getBoolean(JkAlertPreferences.MONITOR,false)
            setOnCheckedChangeListener { _,enabled -> setAlarmMonitoring(enabled) }
        }
        add(alerts,alarmNotificationSwitch,8)
        fun alertCheck(textValue:String,key:String,default:Boolean=false)=
            CheckBox(this).apply {
                text=textValue
                setTextColor(normal)
                isChecked=prefs.getBoolean(key,default)
            }
        fun thresholdField(hintText:String,value:String):EditText =
            input(hintText).apply {
                setText(value)
                inputType=android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSelectAllOnFocus(true)
            }
        val alarmRule=alertCheck("Alarmes signalées par les BMS",
            JkAlertPreferences.ALARM,true)
        add(alerts,alarmRule,8)
        val globalRule=alertCheck("SOC global inférieur au seuil (%)",
            JkAlertPreferences.GLOBAL_SOC)
        add(alerts,globalRule,8)
        val globalField=thresholdField("SOC global minimum (%)",
            prefs.getInt(JkAlertPreferences.GLOBAL_SOC_THRESHOLD,20).toString())
        add(alerts,globalField,4)
        val bmsRule=alertCheck("SOC de chaque BMS inférieur au seuil (%)",
            JkAlertPreferences.BMS_SOC)
        add(alerts,bmsRule,8)
        val bmsField=thresholdField("SOC BMS minimum (%)",
            prefs.getInt(JkAlertPreferences.BMS_SOC_THRESHOLD,20).toString())
        add(alerts,bmsField,4)
        val cellRule=alertCheck("Tension d'une cellule supérieure au seuil (V)",
            JkAlertPreferences.CELL_HIGH)
        add(alerts,cellRule,8)
        val cellField=thresholdField("Seuil maximum cellule (ex. 3,500 V)",
            String.format(Locale.FRANCE,"%.3f",
                prefs.getFloat(JkAlertPreferences.CELL_HIGH_THRESHOLD,3.5f)))
        add(alerts,cellField,4)
        val tempRule=alertCheck("Température d'une sonde ou du MOS supérieure au seuil (°C)",
            JkAlertPreferences.TEMP_HIGH)
        add(alerts,tempRule,8)
        val tempField=thresholdField("Seuil température (°C)",
            String.format(Locale.FRANCE,"%.1f",
                prefs.getFloat(JkAlertPreferences.TEMP_HIGH_THRESHOLD,50f)))
        add(alerts,tempField,4)
        val thresholdFeedback=label(
            "Les seuils sont désactivés par défaut. "+
            "Un franchissement génère une notification avec la batterie, "+
            "la valeur et la cellule ou sonde concernée.",12f,false,subdued)
        add(alerts,thresholdFeedback,8)
        add(alerts,action("Enregistrer les seuils et alertes") {
            val global=JkAlertPreferences.soc(globalField.text.toString())
            val bms=JkAlertPreferences.soc(bmsField.text.toString())
            val cell=JkAlertPreferences.cell(cellField.text.toString())
            val temp=JkAlertPreferences.temperature(tempField.text.toString())
            when {
                global==null -> thresholdFeedback.text=
                    "SOC global : saisis un entier de 1 à 100 %."
                bms==null -> thresholdFeedback.text=
                    "SOC BMS : saisis un entier de 1 à 100 %."
                cell==null -> thresholdFeedback.text=
                    "Tension cellule : saisis une valeur de 2,000 à 4,500 V."
                temp==null -> thresholdFeedback.text=
                    "Température : saisis une valeur de 0 à 120 °C."
                else -> {
                    prefs.edit()
                        .putBoolean(JkAlertPreferences.ALARM,alarmRule.isChecked)
                        .putBoolean(JkAlertPreferences.GLOBAL_SOC,globalRule.isChecked)
                        .putInt(JkAlertPreferences.GLOBAL_SOC_THRESHOLD,global)
                        .putBoolean(JkAlertPreferences.BMS_SOC,bmsRule.isChecked)
                        .putInt(JkAlertPreferences.BMS_SOC_THRESHOLD,bms)
                        .putBoolean(JkAlertPreferences.CELL_HIGH,cellRule.isChecked)
                        .putFloat(JkAlertPreferences.CELL_HIGH_THRESHOLD,cell.toFloat())
                        .putBoolean(JkAlertPreferences.TEMP_HIGH,tempRule.isChecked)
                        .putFloat(JkAlertPreferences.TEMP_HIGH_THRESHOLD,temp.toFloat())
                        .apply()
                    thresholdFeedback.text="Seuils enregistrés pour tous les BMS. "+
                        "Les nouvelles règles sont prises en compte par la surveillance."
                    if(prefs.getBoolean(JkAlertPreferences.MONITOR,false)) {
                        try {
                            val request=Intent(this,JkAlarmMonitorService::class.java)
                                .setAction(JkAlarmMonitorService.ACTION_RELOAD)
                            if(Build.VERSION.SDK_INT>=26)startForegroundService(request)
                            else startService(request)
                        }catch(ex:Exception) {
                            thresholdFeedback.text="Seuils enregistrés. "+
                                "Relance la surveillance : ${ex.javaClass.simpleName}."
                        }
                    }
                }
            }
        },8)
        add(alerts,action("Envoyer une notification de test") {
            sendAlarmTestNotification()
        },6)
        alarmSettingsState=label("Notifications désactivées",12f,false,subdued)
        add(alerts,alarmSettingsState,6)
        add(alerts,label(
            "SOC : alerte strictement sous le seuil. Cellule/température : "+
            "alerte strictement au-dessus. Pour un test sans risque, règle "+
            "temporairement un seuil qui est déjà franchi, enregistre, "+
            "active la surveillance puis ferme l'écran de l'application "+
            "(sans forcer son arrêt). Rétablis ensuite le seuil souhaité. "+
            "Android doit autoriser les notifications et le service actif.",
            12f,color=subdued))
        add(alerts,label(
            "Une seule notification par seuil franchi et par BMS ; pas "+
            "de répétition tant que la condition reste active. "+
            "La perte Wi-Fi, l'absence de VPN et les restrictions batterie "+
            "peuvent empêcher la surveillance. "+
            "Le SOC global exige les données fraîches de TOUS les BMS configurés.",
            12f,color=subdued))
        alarmSettingsState.text=if(prefs.getBoolean(JkAlertPreferences.MONITOR,false))
            "Surveillance demandée · vérifier la notification permanente Android"
            else "Surveillance désactivée"

        // Complete native page construction before selecting the initial home.
        showPage(0)
        val enabled = false
        scanBtn.isEnabled = enabled
        saveBtn.isEnabled = enabled
        wifiBtn.isEnabled = true // Always show actionable diagnostics.
        wifiScanBtn.isEnabled = true
        accessCodeBtn.isEnabled = true
    }

    private fun refreshDebugVisibility() {
        if(::dashboardDebugStatus.isInitialized)
            dashboardDebugStatus.visibility=if(debugEnabled)View.VISIBLE else View.GONE
        if(::telemetryStatus.isInitialized)
            telemetryStatus.visibility=if(debugEnabled)View.VISIBLE else View.GONE
    }
    private fun state(message: String) = runOnUiThread {
        status.text = message
        if(::dashboardDebugStatus.isInitialized) dashboardDebugStatus.text=message
    }

    private fun pairingState(message:String) = runOnUiThread {
        if(::pairingStatus.isInitialized)pairingStatus.text=message
        state(message)
    }

    private fun writeFeedback(message: String) = runOnUiThread {
        lastParameterFeedback=message
        parameterFeedback.text=message
        parameterFeedback.visibility=if(currentTab==2)View.VISIBLE else View.GONE
        status.text=message
        if(::dashboardDebugStatus.isInitialized)dashboardDebugStatus.text=message
    }

    private fun wifiState(message: String) = runOnUiThread {
        wifiStatus.text = message
        status.text = message
        if(::dashboardDebugStatus.isInitialized)dashboardDebugStatus.text=message
    }

    private fun connectSetupAp() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 20)
            state("Autorise « appareils Wi-Fi proches », puis réessaie.")
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 21)
            state("Autorise la localisation nécessaire au dialogue Wi-Fi Android, puis réessaie.")
            return
        }
        val ssid = apName.text.toString().trim()
        val password = setupCode.text.toString().trim().ifBlank { storedCode() }
        if (!Regex("^SmartJK-Setup-[A-F0-9]{6}$").matches(ssid) || password.length !in 8..32) {
            state("Vérifie le réseau SmartJK détecté et son code personnel (8 à 32 caractères).")
            return
        }
        releaseConnection()
        lanVerified = false
        setupTransport = true
        activeSetupCode = password
        activeSetupSsid = ssid
        localHost = ""
        switchingToHome = false
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                apNetwork = network
                state("Wi-Fi temporaire connecté. Vérification du firmware…")
                fetchInfo()
            }
            override fun onLost(network: Network) {
                if (apNetwork == network) apNetwork = null
                if (!switchingToHome) state("Connexion à l'ESP32 perdue.")
            }
            override fun onUnavailable() { state("Connexion refusée ou ESP32 introuvable. Vérifie le code.") }
        }
        callback = cb
        state("Android cherche $ssid. Accepte sa demande de connexion.")
        try { connectivity.requestNetwork(request, cb, 30000) }
        catch (ex: Exception) { state("Échec Wi-Fi : ${ex.message}") }
    }

    private fun releaseConnection() {
        setupTransport = false
        apNetwork = null
        callback?.let {
            try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
    }

    private fun httpCall(method: String, path: String, payload: JSONObject? = null): JSONObject {
        require(path.startsWith("/api/v1/"))
        if (remoteGatewayConnection && !setupTransport) {
            if (!vpnConnected()) throw IllegalStateException("Tailscale doit être actif pour joindre l'ESP32 VPN.")
            if (method != "GET" || path !in setOf(
                    "/api/v1/health", "/api/v1/telemetry", "/api/v1/details")) {
                throw IllegalStateException("Passerelle VPN : seules les lectures JK sont autorisées.")
            }
        } else if (remoteVpnConnection && !setupTransport) {
            if (!vpnConnected()) throw IllegalStateException("VPN absent : aucun accès distant ni envoi de code.")
            if (method != "GET" || path !in setOf(
                    "/api/v1/info", "/api/v1/bms", "/api/v1/telemetry", "/api/v1/details")) {
                throw IllegalStateException("Réglages et réassociation interdits à distance.")
            }
        }
        // Do not bind home LAN requests to a selected Network. On some Android
        // devices or under a VPN, Network.openConnection binds a socket to a
        // network the app is not permitted to use (EPERM).
        //
        // Setup AP is different: WifiNetworkSpecifier creates a dedicated local
        // network that is not the system default and must be used explicitly.
        val conn: HttpURLConnection = try {
            if (setupTransport) {
                val network = apNetwork
                    ?: throw IllegalStateException("Connexion temporaire ESP32 perdue. Relance l'association.")
                if (connectivity.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                    throw IllegalStateException("Connexion Wi-Fi temporaire inactive.")
                }
                network.openConnection(URL("http://192.168.4.1" + path)) as HttpURLConnection
            } else if (remoteGatewayConnection) {
                val host = gatewayHost
                if (!allowedTailnetIp(host) || !vpnConnected()) {
                    throw IllegalStateException("ESP32 VPN inaccessible : vérifie Tailscale et l'adresse 100.x.x.x.")
                }
                val route=JkTransportPolicy.vpnReadPath(activeReaderSlot,path)
                    ?: throw IllegalStateException("Route non autorisée sur la passerelle VPN.")
                URL("http://" + host + ":18767" + route).openConnection() as HttpURLConnection
            } else {
                val host = localHost
                if (!allowedLocalIp(host)) {
                    throw IllegalStateException("Retrouve d'abord l'ESP32 sur le Wi-Fi local.")
                }
                URL("http://" + host + path).openConnection() as HttpURLConnection
            }
        } catch (ex: Exception) {
            throw networkError(ex)
        }
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            if (remoteGatewayConnection && !setupTransport) {
                if (path != "/api/v1/health") {
                    if (!validGatewayToken(gatewaySecret)) throw IllegalStateException("Code VPN invalide.")
                    conn.setRequestProperty("X-Gateway-Token", gatewaySecret)
                }
            } else if (path != "/api/v1/info" &&
                       !path.startsWith("/api/v1/pair/boot/")) {
                conn.setRequestProperty("X-Setup-Code", activeSetupCode)
            }
            if (payload != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val json = JSONObject(body)
            if (code !in 200..299) {
                val upstream = json.optString("error", "HTTP $code")
                val explanation = if (remoteGatewayConnection && !setupTransport)
                    when (upstream) {
                        "invalid_gateway_token" ->
                            "Token VPN refusé (401) : vérifie CONFIG_SE_GATEWAY_TOKEN du fichier de l'ESP32 VPN, pas ton code JK."
                        "vpn_only" ->
                            "ESP32 VPN joignable mais accès refusé (403) : la requête n'est pas reconnue comme provenant du tunnel Tailscale."
                        "source_not_configured" ->
                            "Token VPN accepté, mais la cible JK n'est pas configurée dans l'ESP32 VPN."
                        "upstream_unreachable", "upstream_init_failed" ->
                            "Token VPN accepté, mais l'ESP32 VPN ne peut pas joindre l'ESP32 JK sur son ancienne IP locale. Vérifie CONFIG_SE_JK_IP."
                        "upstream_http_failure" ->
                            "Token VPN accepté, mais le JK a refusé la lecture. Son code a peut-être changé : CONFIG_SE_JK_READ_CODE dans l'ESP32 VPN doit correspondre à ton code JK actuel."
                        else -> "Erreur passerelle VPN HTTP $code : $upstream"
                    }
                else upstream
                throw IllegalStateException(explanation)
            }
            json
        } catch (ex: Exception) {
            throw networkError(ex)
        } finally { conn.disconnect() }
    }

    private fun networkError(ex: Exception): Exception {
        val message = ex.message ?: ""
        if (message.contains("EPERM", true) ||
            message.contains("Binding socket to network", true) ||
            message.contains("Operation not permitted", true)) {
            return IllegalStateException(
                if (remoteGatewayConnection)
                    "Tailscale refuse la connexion à l'ESP32 VPN. Vérifie le VPN et l'adresse privée."
                else if (remoteVpnConnection)
                    "VPN Android refuse la connexion (EPERM). Vérifie que la route du réseau domestique est annoncée et approuvée."
                else
                    "Android refuse le réseau local (EPERM). Autorise le réseau local dans le VPN ou désactive le VPN pour cet essai à domicile.", ex
            )
        }
        val vpnActive = vpnConnected()
        if (vpnActive && (ex is java.net.SocketException ||
                           ex is java.net.UnknownHostException ||
                           ex is java.net.SocketTimeoutException)) {
            return IllegalStateException(
                if (remoteGatewayConnection)
                    "Passerelle ESP32 VPN injoignable. Vérifie Tailscale et l'état du deuxième ESP32."
                else if (remoteVpnConnection)
                    "VPN actif mais réseau de la maison inaccessible. Vérifie la route du réseau privé."
                else
                    "Tentative Wi-Fi locale refusée sous VPN. Pour l'accès extérieur, sélectionne « Accès extérieur — ESP32 VPN » ; aucune reconnexion locale automatique ne sera lancée.", ex
            )
        }
        return ex
    }

    private fun async(task: () -> Unit) {
        io.execute {
            try { task() }
            catch (ex: Exception) { state("Erreur : ${ex.message ?: ex.javaClass.simpleName}") }
        }
    }

    private fun fetchInfo() = async {
        val info = httpCall("GET", "/api/v1/info")
        if (info.optString("product") != "smart-jk-esp32" || !info.optBoolean("setup_mode")) {
            state("Firmware incompatible ou mode configuration désactivé.")
            return@async
        }
        // Authenticated request: do NOT save a code merely because /info answers.
        httpCall("GET", "/api/v1/bms")
        val deviceId = info.optString("device_id")
        if (!Regex("^smartjk-[A-F0-9]{6}$", RegexOption.IGNORE_CASE).matches(deviceId)) {
            state("Identifiant ESP32 invalide.")
            return@async
        }
        savePairing(deviceId, activeSetupSsid, activeSetupCode)
        runOnUiThread {
            setupCode.text.clear()
            state("Passerelle associée et code mémorisé : " + deviceId)
            scanBtn.isEnabled = true
            saveBtn.isEnabled = true
            wifiBtn.isEnabled = true
        }
    }

    private fun connectHomeLan(remote: Boolean = false) {
        if (lanConnectionPending) return
        val ip = lanIp.text.toString().trim()
        if (!allowedLocalIp(ip)) {
            state("Seules les adresses privées de ta passerelle sont autorisées. Aucune IP publique.")
            return
        }
        if (remote && !vpnConnected()) {
            state("Accès distant refusé : active un VPN donnant accès au réseau domestique.")
            return
        }
        if (remote && setupTransport) {
            state("Quitte le réseau de configuration ESP32 avant l'accès distant.")
            return
        }
        // In remote mode, never allow writes (Wi-Fi, BOOT, JK configuration).
        lanConnectionPending = true
        lanVerified = false
        lastLanFirmwareVersion = ""
        releaseConnection()
        remoteVpnConnection = remote
        remoteGatewayConnection = false
        gatewaySecret = ""
        val savedCode = storedCode()
        val typedCode = setupCode.text.toString().trim()
        // Explicitly typed code takes precedence over an old saved code.
        // Persist it only after a successful identity + BMS authentication.
        val recovering = savedCode.isBlank() || typedCode.isNotBlank()
        val code = if (typedCode.isNotBlank()) typedCode else savedCode
        if (code.length !in 8..32) {
            lanConnectionPending = false
            state("Code ESP32 manquant : réassocie une fois sur ton réseau local avant l'accès distant.")
            return
        }
        activeSetupCode = code
        localHost = ip
        state(if (remote) "Connexion via le VPN privé…" else
            if (recovering) "Réassociation sur le réseau domestique…" else
                "Connexion locale à l'ESP32…")
        async {
            try {
                // /info never receives the access secret. Verify identity first.
                val info = httpCall("GET", "/api/v1/info")
                val id = info.optString("device_id")
                val expected = prefs.getString("device_id", "") ?: ""
                if (info.optString("product") != "smart-jk-esp32" ||
                    !Regex("^smartjk-[A-F0-9]{6}$", RegexOption.IGNORE_CASE).matches(id) ||
                    (expected.isNotBlank() && id != expected) ||
                    !info.optBoolean("wifi_connected")) {
                    throw IllegalStateException("Passerelle non reconnue ou identité différente.")
                }
                if (remote && !vpnConnected()) {
                    throw IllegalStateException("Connexion VPN interrompue : accès refusé.")
                }
                val configured = httpCall("GET", "/api/v1/bms")
                val addresses = configured.optJSONArray("bms") ?: JSONArray()
                if (recovering && !remote) {
                    savePairing(id,
                        "SmartJK-Setup-" + id.substringAfter("-").uppercase(Locale.ROOT),
                        code)
                }
                val loaded=JkBmsRegistry.normalized(
                    (0 until addresses.length()).map { i -> addresses.optString(i) })
                prefs.edit()
                    .putString("local_ip", ip)
                    .putString("paired_bms_json", addresses.toString())
                    .apply()
                lanVerified = true
                lastLanFirmwareVersion = info.optString("version")
                runOnUiThread {
                    registeredBms.clear()
                    registeredBms.addAll(loaded)
                    chosen.clear()
                    chosen.addAll(loaded)
                    rememberKnownBms(loaded)
                    lastScannedBms=emptyList()
                    renderBmsRegistry()
                    setupCode.text.clear()
                    scanBtn.isEnabled = true
                    saveBtn.isEnabled = true
                    wifiBtn.isEnabled = !remote
                    if (remote) {
                        remoteStatus.text = "VPN privé : passerelle accessible depuis le téléphone."
                    }
                    state((if (remote) "Accès distant VPN confirmé. " else
                        if (recovering) "Code mémorisé. " else "Connecté automatiquement. ") +
                        id + " · firmware " + info.optString("version") +
                        " · " + chosen.size + " JK mémorisé(s).")
                    refreshTelemetry()
                }
            } finally {
                lanConnectionPending = false
            }
        }
    }

    private fun connectGatewayRemote() {
        if (lanConnectionPending || setupTransport) {
            state("Termine d'abord la connexion ou l'association locale en cours.")
            return
        }
        val host = gatewayIpEdit.text.toString().trim()
        if (!allowedTailnetIp(host)) {
            state("Renseigne l'IP Tailscale 100.64.0.0 à 100.127.255.255 de l'ESP32 VPN.")
            return
        }
        if (!vpnConnected()) {
            state("Active Tailscale sur ton téléphone avant le test distant.")
            return
        }
        val typedSecret = gatewayTokenEdit.text.toString().trim()
        val secret = if (typedSecret.isNotBlank()) typedSecret else storedGatewayToken()
        if (!validGatewayToken(secret)) {
            state("Token VPN absent ou invalide : utilise CONFIG_SE_GATEWAY_TOKEN (20 à 128 caractères) du fichier ESP32 VPN. Le code personnel JK et la clé Tailscale ne fonctionnent pas ici.")
            return
        }
        val expectedDeviceId = prefs.getString("device_id", "") ?: ""
        val knownMacs = try {
            val values = JSONArray(prefs.getString("paired_bms_json", "[]") ?: "[]")
            (0 until values.length()).mapNotNull { i ->
                values.optString(i).uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
            }.toSet()
        } catch (_: Exception) { emptySet<String>() }
        if (expectedDeviceId.isBlank() || knownMacs.isEmpty()) {
            state("Pour sécuriser l'association, connecte d'abord Smart Energy une fois au JK sur ton Wi-Fi domestique, puis réessaie.")
            return
        }
        lanConnectionPending = true
        lanVerified = false
        lastLanFirmwareVersion = ""
        releaseConnection()
        remoteVpnConnection = true
        remoteGatewayConnection = true
        gatewayHost = host
        gatewaySecret = secret
        lastRemoteAttempt=android.os.SystemClock.elapsedRealtime()
        state("Vérification Tailscale → ESP32 VPN → ESP32 JK…")
        async {
            try {
                // Only the public, read-only health request is made before auth.
                val health = httpCall("GET", "/api/v1/health")
                if (health.optString("product") != "smart-energy-vpn-gateway" ||
                    !health.optBoolean("vpn_only") ||
                    !health.optBoolean("read_only") ||
                    !health.optBoolean("vpn_connected")) {
                    throw IllegalStateException("Passerelle VPN incompatible ou identité non vérifiée.")
                }
                val telemetry = httpCall("GET", "/api/v1/telemetry")
                if (telemetry.optString("source") != "jk02-ffe1-read-only" ||
                    telemetry.optJSONArray("devices") == null) {
                    throw IllegalStateException("Données JK non reconnues ; connexion refusée.")
                }
                val devices = telemetry.getJSONArray("devices")
                val matchesPairing = (0 until devices.length()).any { i ->
                    val mac = devices.optJSONObject(i)?.optString("mac")
                        ?.uppercase(Locale.ROOT) ?: ""
                    knownMacs.contains(mac)
                }
                if (!matchesPairing) {
                    throw IllegalStateException("La passerelle VPN ne présente pas la batterie JK associée localement. Vérifie sa configuration.")
                }
                if (!vpnConnected()) throw IllegalStateException("Tailscale s'est déconnecté.")
                // Save only after successfully checking gateway identity,
                // read token, and consistency with a locally paired JK.
                saveGatewayToken(secret)
                prefs.edit().putString("remote_gateway_ip", host).apply()
                lanVerified = true
                runOnUiThread {
                    gatewayTokenEdit.text.clear()
                    remoteStatus.text = "ESP32 VPN connecté en lecture seule."
                    wifiBtn.isEnabled = false
                    state("Accès distant sécurisé confirmé · JK associé reconnu.")
                    showTelemetry(telemetry,newRead=true)
                }
            } finally {
                lanConnectionPending = false
            }
        }
    }

    private fun beginBootPairing() {
        // Always show a dialog. Previous versions silently returned on a
        // missing SSID or Android location permission, while errors appeared
        // several screens lower and made the BOOT button seem unresponsive.
        val slot=activeReaderSlot
        val ssid=apName.text.toString().trim()
        val expected=Regex("^SmartJK-Setup-[A-Fa-f0-9]{6}$")
        if(!expected.matches(ssid)) {
            pairingState("ESP $slot : aucun réseau SmartJK sélectionné. "+
                "Clique d'abord sur « 1 · Rechercher mon ESP32 ».")
            AlertDialog.Builder(this)
                .setTitle("ESP $slot — étape 1 : recherche")
                .setMessage("Choisis d'abord le réseau Wi-Fi de TON ESP32 "+
                    "avec « Rechercher mon ESP32 ».\n\n"+
                    "S'il n'apparaît pas : laisse l'ESP32 allumé, maintiens "+
                    "BOOT pendant 4 secondes, puis relâche et relance la "+
                    "recherche. Le réseau temporaire reste ouvert 2 minutes.")
                .setNegativeButton("Fermer",null)
                .setPositiveButton("Rechercher mon ESP32") { _,_-> findNearbyEsp32() }
                .show()
            return
        }
        val owner=JkNearbyWifiPolicy.assignedToAnotherSlot(
            ssid,slot,assignedSetupSlots())
        if(owner!=null) {
            pairingState("Ce réseau appartient déjà à ESP $owner : sélection refusée.")
            AlertDialog.Builder(this)
                .setTitle("Lecteur déjà associé")
                .setMessage("$ssid est enregistré sur ESP $owner. "+
                    "Ne le réassocie pas sur ESP $slot. Recherche le réseau "+
                    "distinct de ton nouveau lecteur.")
                .setPositiveButton("Compris",null).show()
            return
        }
        val form=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(d(18),d(4),d(18),d(4))
        }
        add(form,label("ESP $slot : $ssid",15f,true))
        add(form,label("1. Renseigne d'abord ton code et le Wi-Fi maison.\n"+
            "2. Sur cet ESP32 DÉJÀ ALLUMÉ, maintiens BOOT 4 secondes et "+
            "relâche.\n3. Clique « Se connecter » sans tarder et accepte "+
            "la demande de connexion Wi-Fi Android.\n4. Lorsque « Code prêt » "+
            "apparaît, maintiens BOOT une DEUXIÈME fois pendant 2 secondes, "+
            "relâche, puis utilise « 3 · Après le 2e BOOT : confirmer ».\n"+
            "Le réseau temporaire reste ouvert 2 minutes : prépare les "+
            "informations avant le premier BOOT.",13f,color=subdued))
        val newCode=input("Code personnel ESP $slot (8 à 32 caractères)",true)
        val wifiSsid=input("Wi-Fi maison 2,4 GHz (nouveau lecteur)")
        val wifiPassword=input("Mot de passe Wi-Fi maison",true)
        wifiSsid.setText(homeSsid.text.toString())
        add(form,newCode,6)
        add(form,wifiSsid,6)
        add(form,wifiPassword,6)
        add(form,label("Sur un ESP32 neuf, renseigne obligatoirement le Wi-Fi "+
            "maison. Pour un lecteur qui connaît déjà ta box, tu peux laisser "+
            "les deux champs Wi-Fi vides.",12f,color=subdued))
        val error=label("",12f,false,Color.rgb(255,170,150))
        add(form,error,6)
        val scrollingForm=ScrollView(this).apply {
            isFillViewport=false
            addView(form)
        }
        val dialog=AlertDialog.Builder(this)
            .setTitle("Associer ESP $slot avec BOOT")
            .setView(scrollingForm)
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Se connecter à l'ESP32",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(activeReaderSlot!=slot) {
                    error.text="Le lecteur sélectionné a changé : relance l'association."
                    return@setOnClickListener
                }
                val code=newCode.text.toString()
                val home=wifiSsid.text.toString().trim()
                val pass=wifiPassword.text.toString()
                when {
                    code.length !in 8..32 || code.any { it.code !in 33..126 } -> {
                        error.text="Choisis un code de 8 à 32 caractères ASCII sans espaces."
                        return@setOnClickListener
                    }
                    home.isNotBlank() && pass.length !in 8..63 -> {
                        error.text="Le mot de passe Wi-Fi doit contenir de 8 à 63 caractères."
                        return@setOnClickListener
                    }
                    home.isBlank() && pass.isNotBlank() -> {
                        error.text="Saisis le nom de ton réseau Wi-Fi ou efface les deux champs."
                        return@setOnClickListener
                    }
                }
                if(Build.VERSION.SDK_INT >= 33 &&
                   checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                       PackageManager.PERMISSION_GRANTED) {
                    error.text="Autorise les appareils Wi-Fi proches, puis reclique "+
                        "sur « Se connecter »."
                    requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),20)
                    return@setOnClickListener
                }
                if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED) {
                    error.text="Autorise la localisation Android, puis reclique "+
                        "sur « Se connecter »."
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),21)
                    return@setOnClickListener
                }
                dialog.dismiss()
                pairingState("ESP $slot : recherche du Wi-Fi temporaire $ssid. "+
                    "Accepte la demande Android pour continuer.")
                connectBootPairing(ssid,code,home,pass)
            }
        }
        dialog.show()
    }

    private fun connectBootPairing(ssid:String,code:String,home:String,password:String) {
        releaseConnection()
        lanVerified=false
        remoteVpnConnection=false
        remoteGatewayConnection=false
        setupTransport=true
        switchingToHome=false
        bootPairTicket=0
        bootPairCode=code
        bootPairDeviceId=""
        activeSetupSsid=ssid
        localHost=""
        // Deliberately NO WPA2 password: only the physically confirmed,
        // short-lived, single-client BOOT window uses this transport.
        val spec=WifiNetworkSpecifier.Builder().setSsid(ssid).build()
        val req=NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(spec).build()
        val cb=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:Network) {
                apNetwork=network
                pairingState("Wi-Fi temporaire BOOT connecté : association en préparation…")
                async {
                    try {
                        val info=httpCall("GET","/api/v1/info")
                        val deviceId=info.optString("device_id")
                        val expected=prefs.getString("device_id","") ?: ""
                        val foreignOwner=(1..3).firstOrNull { slot ->
                            slot!=activeReaderSlot &&
                            (prefs.getString(slotKey(slot,"device_id"),"") ?: "")
                                .equals(deviceId,ignoreCase=true) &&
                            deviceId.isNotBlank()
                        }
                        if(foreignOwner!=null)
                            throw IllegalStateException("Cet ESP32 est déjà associé "+
                                "au lecteur ESP $foreignOwner. Impossible de le "+
                                "réassocier comme ESP $activeReaderSlot.")
                        if(info.optString("product")!="smart-jk-esp32" ||
                            !info.optBoolean("physical_pairing_mode") ||
                            !info.optBoolean("physical_pairing_requires_second_boot") ||
                            !Regex("^smartjk-[A-F0-9]{6}$",RegexOption.IGNORE_CASE).matches(deviceId) ||
                            (expected.isNotBlank() && expected!=deviceId)) {
                            throw IllegalStateException("ESP32 non reconnu ou fenêtre BOOT expirée.")
                        }
                        val data=JSONObject().put("device_id",deviceId)
                            .put("new_code",code)
                            .put("ssid",home).put("wifi_password",password)
                        val staged=httpCall("POST","/api/v1/pair/boot/stage",data)
                        if(!staged.optBoolean("staged") || staged.optLong("ticket",0)==0L)
                            throw IllegalStateException("Association non préparée.")
                        bootPairTicket=staged.optLong("ticket")
                        bootPairDeviceId=deviceId
                        pairingState("ESP $activeReaderSlot : code prêt. Maintiens BOOT une deuxième fois pendant 2 secondes, " +
                              "relâche, puis utilise le bouton « Après le 2e BOOT ».")
                    } catch(ex:Exception) {
                        bootPairTicket=0
                        pairingState("Association BOOT impossible : "+(ex.message ?: "erreur") +
                            ". Vérifie que tu as maintenu BOOT 4 s et que le Wi-Fi temporaire est visible.")
                    }
                }
            }
            override fun onLost(network:Network) {
                if(apNetwork==network)apNetwork=null
                if(!switchingToHome)pairingState("Réseau BOOT perdu ou délai expiré : réessaie l'association.")
            }
            override fun onUnavailable() {
                pairingState("Réseau BOOT introuvable. Maintiens BOOT 4 s sur l'ESP32 allumé, puis réessaie.")
            }
        }
        callback=cb
        pairingState("Recherche du réseau temporaire ouvert après BOOT… Accepte la demande Android.")
        try{connectivity.requestNetwork(req,cb,30000)}
        catch(ex:Exception){pairingState("Connexion BOOT impossible : "+(ex.message ?: "réseau"))}
    }

    private fun commitBootPairing() {
        val ticket=bootPairTicket
        val id=bootPairDeviceId
        val code=bootPairCode
        if(ticket==0L || id.isBlank() || code.length !in 8..32 ||
            !setupTransport || apNetwork==null) {
            pairingState("Aucune association BOOT en attente. Commence par l’étape 2 « Associer avec BOOT ».")
            return
        }
        async {
            try {
                val check=httpCall("GET","/api/v1/pair/boot/status?ticket=$ticket")
                if(!check.optBoolean("confirmed_by_boot")) {
                    pairingState("Seconde confirmation absente : maintiens BOOT 2 secondes puis relâche.")
                    return@async
                }
                val result=httpCall("POST","/api/v1/pair/boot/commit",
                    JSONObject().put("ticket",ticket))
                if(!result.optBoolean("paired") || result.optString("device_id")!=id)
                    throw IllegalStateException("L'ESP32 n'a pas confirmé l'enregistrement.")
                // Store only after the physical commit succeeds on this device.
                savePairing(id,activeSetupSsid,code)
                bootPairTicket=0;bootPairCode="";bootPairDeviceId=""
                switchingToHome=true
                releaseConnection()
                prefs.edit().remove("local_ip").apply()
                runOnUiThread {
                    setupCode.text.clear()
                    homePassword.text.clear()
                    pairingState("ESP $activeReaderSlot : association réussie ! Code mémorisé et BMS conservés. " +
                          "L'ESP32 rejoint maintenant ton Wi-Fi 2,4 GHz.")
                    uiHandler.postDelayed({ discoverHomeLan() },9000)
                }
            }catch(ex:Exception){
                pairingState("Confirmation BOOT impossible : "+(ex.message ?: "erreur") +
                    ". Ne réappuie pas plusieurs fois sur BOOT ; vérifie l'état avant de relancer.")
            }
        }
    }

    private fun discoverHomeLan() {
        val code = storedCode()
        if (code.isBlank()) {
            state("Code ESP32 manquant. Saisis son code original dans la section 2, puis connecte-toi via son IP dans la section 1.")
            return
        }
        if (discoverListener != null) {
            state("Recherche déjà en cours.")
            return
        }
        val nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        var found = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                state("Recherche des passerelles Smart JK sur le réseau local…")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                state("Recherche réseau indisponible : indique l'adresse IP manuellement.")
                try { nsd.stopServiceDiscovery(this) } catch (_: Exception) {}
                discoverListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoverListener = null
            }
            override fun onDiscoveryStopped(serviceType: String) { discoverListener = null }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val expected = prefs.getString("device_id", "") ?: ""
                if (found || !serviceInfo.serviceName.startsWith("smartjk-", true) ||
                    (expected.isNotBlank() && !serviceInfo.serviceName.equals(expected, true))) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host
                        if (found || host !is Inet4Address) return
                        val ip = host.hostAddress ?: return
                        if (!allowedLocalIp(ip)) return
                        found = true
                        runOnUiThread {
                            lanIp.setText(ip)
                            connectHomeLan() // verifies stored device ID before accepting IP
                        }
                    }
                })
            }
        }
        discoverListener = listener
        try {
            nsd.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            uiHandler.postDelayed({
                if (discoverListener === listener) {
                    try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
                    discoverListener = null
                    if (!found) state("Aucune passerelle trouvée : renseigne son IP une fois.")
                }
            }, 12000)
        } catch (ex: Exception) {
            discoverListener = null
            state("Recherche impossible : " + (ex.message ?: "erreur système"))
        }
    }

    private fun assignedSetupSlots():Map<Int,String> =
        (1..3).associateWith { slot ->
            if(slot==activeReaderSlot)
                prefs.getString("setup_ssid","") ?: ""
            else prefs.getString(slotKey(slot,"setup_ssid"),"") ?: ""
        }

    private fun findNearbyEsp32() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 40)
            pairingState("Autorise « Appareils Wi-Fi proches », puis relance la recherche.")
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 41)
            pairingState("Autorise la localisation pour rechercher les Wi-Fi SmartJK proches, puis relance la recherche.")
            return
        }
        val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!location.isLocationEnabled) {
            pairingState("Active la localisation Android pour rechercher les ESP32 proches (obligation Android).")
            return
        }
        if (!wifiManager.isWifiEnabled) {
            pairingState("Active le Wi-Fi du téléphone avant de rechercher les ESP32.")
            return
        }
        val scanSlot=activeReaderSlot
        pairingState("ESP $scanSlot : recherche des réseaux SmartJK. Le lecteur doit être allumé.")
        val scanStarted=try { wifiManager.startScan() } catch (_:SecurityException) {
            pairingState("Autorisation Wi-Fi insuffisante pour rechercher les ESP32.")
            return
        }
        uiHandler.postDelayed({
            if(activeReaderSlot!=scanSlot)return@postDelayed
            try {
                // Android may return results from a previous scan when Wi-Fi
                // throttles scanning. The timestamp is microseconds since boot.
                val nowUs=android.os.SystemClock.elapsedRealtimeNanos()/1000L
                val candidates=JkNearbyWifiPolicy.candidates(
                    wifiManager.scanResults.map { item ->
                        val ageMs=if(item.timestamp>0L && nowUs>=item.timestamp)
                            (nowUs-item.timestamp)/1000L else Long.MAX_VALUE
                        JkNearbyWifi(item.SSID,item.level,ageMs)
                    })
                val assigned=assignedSetupSlots()
                if(candidates.isEmpty()) {
                    pairingState("Aucun SmartJK-Setup détecté pour ESP $scanSlot. "+
                          "Laisse seulement le nouvel ESP32 alimenté et regarde dans les Wi-Fi "+
                          "du téléphone. Sur l'ESP32 allumé, BOOT 4 s ouvre un réseau temporaire.")
                    return@postDelayed
                }
                val newFresh=candidates.filter {
                    JkNearbyWifiPolicy.isFresh(it) &&
                    JkNearbyWifiPolicy.assignedToAnotherSlot(
                        it.ssid,activeReaderSlot,assigned)==null
                }
                if(newFresh.isEmpty()) {
                    val owners=candidates.mapNotNull {
                        JkNearbyWifiPolicy.assignedToAnotherSlot(
                            it.ssid,activeReaderSlot,assigned)
                    }.distinct()
                    pairingState("Aucun NOUVEL ESP32 identifié pour ESP $scanSlot. "+
                          (if(owners.isNotEmpty()) "Seul le lecteur ESP ${owners.joinToString("/")} "+
                              "déjà configuré apparaît. " else "")+
                          (if(!scanStarted) "Android a limité le nouveau scan Wi-Fi. " else "")+
                          "Débranche temporairement ESP 1 et vérifie le réseau SmartJK-Setup "+
                          "du nouveau lecteur dans les paramètres Wi-Fi du téléphone.")
                } else {
                    pairingState("${newFresh.size} réseau(x) SmartJK candidat(s) pour ESP $scanSlot. "+
                          "Choisis le nouveau lecteur par son identifiant.")
                }
                // A single new, fresh unassigned reader is unambiguous.
                // Do not make users select ESP 1 again from a list.
                val unique=JkNearbyWifiPolicy.uniqueFreshUnclaimed(
                    candidates,scanSlot,assigned)
                if(unique!=null) {
                    useFoundEsp32(unique.ssid)
                    return@postDelayed
                }
                val options=candidates.map {
                    JkNearbyWifiPolicy.label(it,activeReaderSlot,assigned)
                }
                AlertDialog.Builder(this)
                    .setTitle("ESP $activeReaderSlot — lecteurs SmartJK détectés")
                    .setMessage("Les réseaux déjà enregistrés sur un autre lecteur sont "+
                        "signalés. Les anciens résultats du cache Wi-Fi Android "+
                        "ne sont pas sélectionnables.")
                    .setItems(options.toTypedArray()) { _,index ->
                        val found=candidates[index]
                        when {
                            !JkNearbyWifiPolicy.isFresh(found) ->
                                pairingState("Réseau ${found.ssid} issu d'un ancien scan Android. "+
                                      "Actualise le Wi-Fi ou utilise les réglages Wi-Fi du téléphone.")
                            JkNearbyWifiPolicy.assignedToAnotherSlot(
                                found.ssid,activeReaderSlot,assigned)!=null ->
                                pairingState("${found.ssid} appartient déjà à ESP "+
                                      JkNearbyWifiPolicy.assignedToAnotherSlot(
                                          found.ssid,activeReaderSlot,assigned)+
                                      ". Choisis le SSID propre au nouveau lecteur.")
                            else -> useFoundEsp32(found.ssid)
                        }
                    }
                    .setNegativeButton("Fermer",null)
                    .show()
            } catch (_:SecurityException) {
                pairingState("Vérifie les autorisations de localisation et le Wi-Fi Android.")
            }
        }, 3500)
    }

    private fun useFoundEsp32(ssid:String) {
        val owner=JkNearbyWifiPolicy.assignedToAnotherSlot(
            ssid,activeReaderSlot,assignedSetupSlots())
        if(owner!=null) {
            pairingState("Ce SmartJK est déjà associé à ESP $owner. Impossible de le "+
                  "choisir comme ESP $activeReaderSlot sans retirer l'association précédente.")
            return
        }
        apName.setText(ssid.uppercase(Locale.ROOT).replace("SMARTJK-SETUP-","SmartJK-Setup-"))
        pairingState("ESP $activeReaderSlot trouvé : $ssid. Pour l'associer, " +
              "maintiens BOOT pendant 4 s sur CET ESP32 allumé puis utilise "+
              "« Associer avec BOOT ». Un second appui confirme la configuration.")
    }

    private fun scanRouterWifi() {
        if (!setupTransport && !lanVerified) {
            wifiState("Connecte d'abord l'ESP32 dans la section 1 ou 2, afin qu'il recherche les réseaux Wi-Fi à sa portée.")
            return
        }
        wifiScanBtn.isEnabled = false
        async {
            try {
                wifiState("L'ESP32 recherche les réseaux Wi-Fi 2,4 GHz accessibles…")
                val result = httpCall("GET", "/api/v1/wifi/scan")
                val networks = result.optJSONArray("networks") ?: JSONArray()
                val options = ArrayList<Pair<String, Int>>()
                for (i in 0 until networks.length()) {
                    val item = networks.getJSONObject(i)
                    val ssid = item.optString("ssid")
                    if (ssid.isNotBlank()) options.add(Pair(ssid, item.optInt("rssi")))
                }
                runOnUiThread {
                    wifiNetworks.removeAllViews()
                    if (options.isEmpty()) {
                        homeSsid.visibility = View.VISIBLE
                        wifiState("Aucun Wi-Fi 2,4 GHz trouvé. Vérifie la portée de la box ou saisis le nom manuellement.")
                    } else {
                        for ((index, entry) in options.withIndex()) {
                            val (ssid, rssi) = entry
                            val option = RadioButton(this).apply {
                                text = "$ssid  ·  $rssi dBm"
                                setTextColor(normal)
                                id = View.generateViewId()
                                setPadding(d(6), d(7), d(6), d(7))
                                setOnClickListener {
                                    homeSsid.setText(ssid)
                                    wifiState("Réseau sélectionné : " + ssid + ". Renseigne seulement son mot de passe Wi-Fi.")
                                }
                            }
                            wifiNetworks.addView(option)
                            if (index == 0) {
                                option.isChecked = true
                                homeSsid.setText(ssid)
                            }
                        }
                        wifiState("${options.size} réseaux 2,4 GHz trouvés. Choisis ton réseau puis renseigne son mot de passe.")
                    }
                }
            } catch (ex: Exception) {
                runOnUiThread { homeSsid.visibility = View.VISIBLE }
                wifiState("Recherche Wi-Fi impossible : " +
                    (ex.message ?: ex.javaClass.simpleName) +
                    ". Vérifie que le firmware ESP32 est en v0.1.4-dev. Tu peux saisir le réseau manuellement.")
            } finally {
                runOnUiThread { wifiScanBtn.isEnabled = true }
            }
        }
    }

    private fun changeDeviceCode() {
        val newCode = accessCodeNew.text.toString()
        if (newCode.length !in 8..32 || newCode.any { it.code !in 33..126 }) {
            state("Le code ESP32 doit contenir 8 à 32 caractères sans espaces. « 1234 » est trop court pour protéger son Wi-Fi.")
            return
        }
        if (!setupTransport && !lanVerified) {
            state("Connecte d'abord l'ESP32 avant de changer son code.")
            return
        }
        accessCodeBtn.isEnabled = false
        async {
            try {
                val info = httpCall("GET", "/api/v1/info")
                if (!info.optBoolean("change_code")) {
                    throw IllegalStateException("Mets le firmware ESP32 à jour vers la v0.1.4-dev.")
                }
                val deviceId = info.optString("device_id")
                val saved = prefs.getString("device_id", "") ?: ""
                if (saved.isNotBlank() && saved != deviceId) throw IllegalStateException("Passerelle différente de l'appareil associé.")
                val response = httpCall("POST", "/api/v1/access", JSONObject().put("new_code", newCode))
                if (!response.optBoolean("updated")) throw IllegalStateException("Changement non confirmé par l'ESP32.")
                savePairing(deviceId, prefs.getString("setup_ssid", "") ?: "", newCode)
                runOnUiThread { accessCodeNew.text.clear() }
                state("Code ESP32 modifié et sauvegardé dans l'application. Tu n'auras pas à le retaper.")
                if (setupTransport) releaseConnection()
            } catch (ex: Exception) {
                state("Échec du changement du code : " + (ex.message ?: ex.javaClass.simpleName))
            } finally {
                runOnUiThread { accessCodeBtn.isEnabled = true }
            }
        }
    }

    // Only the directly paired ESP32 may change the persistent JK list.
    // The dedicated VPN gateway stays strictly read-only.
    private fun canEditBmsRegistry(): Boolean =
        !remoteGatewayConnection && !remoteVpnConnection &&
            (lanVerified || (setupTransport && apNetwork!=null))

    private fun savedMacsFromResponse(response: JSONObject): List<String> {
        val json=response.optJSONArray("bms") ?: JSONArray()
        return JkBmsRegistry.normalized(
            (0 until json.length()).map { i -> json.optString(i) })
    }

    private fun rememberedBms(): List<String> {
        return try {
            val json=JSONArray(prefs.getString("known_jk_macs", "[]") ?: "[]")
            JkBmsRegistry.normalized(
                (0 until json.length()).map { i -> json.optString(i) })
        } catch (_: Exception) { emptyList() }
    }

    private fun rememberKnownBms(macs: Iterable<String>) {
        val keep=JkBmsRegistry.remember(rememberedBms(),macs)
        val json=JSONArray()
        keep.forEach { json.put(it) }
        prefs.edit().putString("known_jk_macs",json.toString()).apply()
    }

    private fun renderBmsRegistry() {
        // Read-only scan results are merged with the authoritative list, NOT
        // used to decide which previously paired BMSs still exist.
        val rows=JkBmsRegistry.merge(registeredBms,rememberedBms(),lastScannedBms)
        scanChoices.clear()
        scanPanel.removeAllViews()
        if(rows.isEmpty()) {
            add(scanPanel,label("Aucun BMS mémorisé. Lance une recherche Bluetooth pour en ajouter.",color=subdued))
            return
        }
        val pending=chosen.toSet()
        add(scanPanel,label(
            "${registeredBms.size} BMS enregistrés dans l'ESP32 · ${pending.size} sélectionnés pour le prochain enregistrement.",
            13f,color=subdued),8)
        rows.forEach { row ->
            val origin=when {
                row.registeredOnEsp -> "Enregistré dans l'ESP32 (même si non détecté au scan)"
                row.knownOnPhone -> "Ancien BMS connu · réassociation possible"
                else -> "Détecté maintenant · non enregistré"
            }
            val quality=if(row.discoveredNow && row.rssi!=null)
                " · ${row.rssi} dBm" else ""
            val check=CheckBox(this).apply {
                text="${displayBmsName(row.mac,row.name)}\n${row.mac}${quality}\n${origin}"
                setTextColor(normal)
                isChecked=pending.contains(row.mac)
                setOnCheckedChangeListener { _,checked ->
                    if(checked) chosen.add(row.mac) else chosen.remove(row.mac)
                }
            }
            add(scanPanel,check,6)
            add(scanPanel,action("Renommer ce BMS") { renameBms(row.mac) },2)
            scanChoices.add(check)
        }
    }

    private fun loadSavedBms() {
        if(!canEditBmsRegistry()) {
            state("La gestion des BMS est disponible uniquement en Wi-Fi local sur l'ESP32 JK. Le VPN reste en lecture seule.")
            return
        }
        state("Lecture de la liste sauvegardée directement dans l'ESP32…")
        async {
            val confirmed=savedMacsFromResponse(httpCall("GET","/api/v1/bms"))
            runOnUiThread {
                registeredBms.clear()
                registeredBms.addAll(confirmed)
                chosen.clear()
                chosen.addAll(confirmed)
                lastScannedBms=emptyList()
                rememberKnownBms(confirmed)
                renderBmsRegistry()
                state("${confirmed.size} BMS enregistrés récupérés. Décoche un JK puis sauvegarde pour le retirer, ou réactive une ancienne MAC.")
            }
        }
    }

    private fun scanBle() {
        if(!canEditBmsRegistry()) {
            state("La recherche et la sélection sont réservées au réseau local. Le VPN ne peut pas modifier les BMS.")
            return
        }
        state("Recherche de nouveaux appareils BLE ; les JK déjà connectés restent visibles dans la liste sauvegardée…")
        async {
            val confirmed=savedMacsFromResponse(httpCall("GET","/api/v1/bms"))
            var scanFailure: String?=null
            val newlySeen=try {
                val json=httpCall("GET","/api/v1/bms/scan").optJSONArray("devices") ?: JSONArray()
                (0 until json.length()).mapNotNull { i ->
                    val item=json.optJSONObject(i) ?: return@mapNotNull null
                    val mac=JkBmsRegistry.validMac(item.optString("mac"))
                        ?: return@mapNotNull null
                    JkBmsRegistry.ScanItem(mac,item.optString("name"),
                        item.optInt("rssi"))
                }
            } catch(ex:Exception) {
                scanFailure=ex.message ?: ex.javaClass.simpleName
                emptyList()
            }
            runOnUiThread {
                registeredBms.clear()
                registeredBms.addAll(confirmed)
                rememberKnownBms(confirmed)
                lastScannedBms=newlySeen
                // Keep pending checkbox edits when scanning more devices.
                renderBmsRegistry()
                state(if(scanFailure!=null)
                    "${confirmed.size} JK sauvegardés affichés malgré l'échec du scan : $scanFailure"
                else "${confirmed.size} JK sauvegardés · ${newlySeen.size} périphériques vus au scan. Les JK connectés restent sélectionnables.")
            }
        }
    }

    private fun saveSelection() {
        if(!canEditBmsRegistry()) {
            state("Suppression et association de BMS interdites à distance : connecte-toi en Wi-Fi local.")
            return
        }
        val wanted=JkBmsRegistry.normalized(chosen)
        if(wanted.size!=chosen.size || wanted.size>2) {
            state("Sélection invalide : au maximum deux BMS par lecteur ESP32 JK.")
            return
        }
        val removing=registeredBms.filter { it !in wanted }
        fun commit() {
            saveBtn.isEnabled=false
            state("Enregistrement de ${wanted.size} BMS dans l'ESP32…")
            async {
                try {
                    val json=JSONArray()
                    wanted.forEach { json.put(it) }
                    val response=httpCall("PUT","/api/v1/bms",
                        JSONObject().put("bms",json))
                    if(response.optInt("saved",-1)!=wanted.size)
                        throw IllegalStateException("L'ESP32 n'a pas confirmé le nombre de BMS.")
                    // Read back the NVS-backed list before claiming success.
                    val confirmed=savedMacsFromResponse(httpCall("GET","/api/v1/bms"))
                    if(confirmed.toSet()!=wanted.toSet())
                        throw IllegalStateException("Liste ESP32 différente après enregistrement. Recharge la liste.")
                    val stored=JSONArray()
                    confirmed.forEach { stored.put(it) }
                    prefs.edit().putString("paired_bms_json",stored.toString()).apply()
                    runOnUiThread {
                        rememberKnownBms(registeredBms + confirmed)
                        registeredBms.clear()
                        registeredBms.addAll(confirmed)
                        chosen.clear()
                        chosen.addAll(confirmed)
                        // Do not remove absent MACs from phone history.
                        renderBmsRegistry()
                        state("${confirmed.size} BMS sauvegardés dans l'ESP32. ${removing.size} retiré(s) de la connexion ; ils restent réactivables ici.")
                        refreshTelemetry()
                    }
                } catch(ex:Exception) {
                    state("Enregistrement non vérifié : ${ex.message}. Recharge la liste avant de réessayer.")
                } finally {
                    runOnUiThread { saveBtn.isEnabled=true }
                }
            }
        }
        if(removing.isNotEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Retirer ${removing.size} BMS de l'ESP32 ?")
                .setMessage("Le ou les BMS décochés seront déconnectés et retirés de la liste sauvegardée dans l'ESP32.\n\nLeurs réglages internes et leurs protections ne seront pas modifiés. Leurs adresses resteront connues du téléphone pour une réassociation ultérieure.")
                .setNegativeButton("Annuler",null)
                .setPositiveButton("Retirer et enregistrer") { _,_ -> commit() }
                .show()
        } else commit()
    }

    private fun sendHomeWifi() {
        val ssid = homeSsid.text.toString().trim()
        val pass = homePassword.text.toString()
        if (ssid.isBlank() || ssid.length > 32 || pass.length !in 8..63) {
            wifiState("Renseigne un SSID et un mot de passe Wi-Fi de 8 à 63 caractères.")
            return
        }
        val viaSetup = setupTransport
        if (!viaSetup && !lanVerified) {
            wifiState("Connecte d'abord la passerelle dans la section 1 (Wi-Fi maison), ou utilise le mode configuration dans la section 2.")
            return
        }
        wifiBtn.isEnabled = false
        async {
            try {
                wifiState("Vérification de l'ESP32 et enregistrement Wi-Fi en cours…")
                if (!viaSetup) {
                    val info = httpCall("GET", "/api/v1/info")
                    if (info.optString("product") != "smart-jk-esp32" ||
                        info.optString("device_id") != prefs.getString("device_id", "") ||
                        !info.optBoolean("wifi_connected")) {
                        throw IllegalStateException("L'ESP32 local ne correspond pas à celui associé.")
                    }
                    if (!supportsLanWifiUpdate(info.optString("version"))) {
                        throw IllegalStateException("Le firmware ESP32 doit être en v0.1.3 ou supérieur pour changer le Wi-Fi depuis le réseau de la maison. L'ancien firmware ne le permet qu'en mode configuration.")
                    }
                }
                val response = httpCall("POST", "/api/v1/wifi",
                    JSONObject().put("ssid", ssid).put("password", pass))
                if (!response.optBoolean("ok")) {
                    throw IllegalStateException("L'ESP32 n'a pas confirmé l'enregistrement.")
                }
                runOnUiThread {
                    homePassword.text.clear()
                    setupCode.text.clear()
                }
                if (viaSetup) {
                    switchingToHome = true
                    releaseConnection()
                    wifiState("Wi-Fi enregistré. L'ESP32 redémarre. Reconnecte ton téléphone à la box ; la passerelle sera ensuite retrouvée sur le réseau local.")
                    uiHandler.postDelayed({ discoverHomeLan() }, 9000)
                } else {
                    lanVerified = false
                    wifiState("Wi-Fi enregistré. L'ESP32 redémarre : vérification de sa reconnexion au réseau de la maison…")
                    uiHandler.postDelayed({
                        if (allowedLocalIp(localHost)) connectHomeLan() else discoverHomeLan()
                    }, 9000)
                }
            } catch (ex: Exception) {
                wifiState("Enregistrement Wi-Fi impossible : " +
                    (ex.message ?: ex.javaClass.simpleName) + ". Aucun succès confirmé.")
            } finally {
                runOnUiThread { wifiBtn.isEnabled = true }
            }
        }
    }

    private fun formatValue(value: Double, decimals: Int = 2): String =
        String.format(Locale.FRANCE, "%." + decimals + "f", value)

    private fun readerStatus(raw: String): String = when (raw) {
        "waiting_first_poll" -> "En attente de la première connexion Bluetooth"
        "searching_ble" -> "Recherche du JK en Bluetooth"
        "ble_not_seen" -> "JK hors de portée ou déjà connecté à un autre appareil"
        "connecting_ble" -> "Connexion Bluetooth en cours"
        "ble_connect_failed" -> "Échec de la connexion Bluetooth"
        "ble_busy_retry" -> "Connexion NimBLE occupée : attente et récupération"
        "ble_busy_retry_exhausted" -> "Initiateur NimBLE déjà occupé (BLE 2)"
        "ble_gap_busy_retry" -> "Tentative NimBLE bloquée : récupération du contrôleur"
        "ble_client_unavailable" -> "Bluetooth indisponible sur l'ESP32"
        "ffe1_unavailable" -> "Service Bluetooth FFE1 non trouvé"
        "ble_subscribe_failed" -> "Notifications Bluetooth indisponibles"
        "waiting_frames" -> "En attente des trames JK"
        "no_valid_jk_frame" -> "Aucune trame JK valide reçue"
        "unknown_jk_layout" -> "Trames reçues, mais format JK non reconnu"
        "valid_sample" -> "Mesures reçues"
        "stale" -> "Mesures trop anciennes, connexion JK à vérifier"
        else -> raw.ifBlank { "En attente" }
    }

    private fun refreshTelemetry() {
        if (telemetryBusy) return
        if (!lanVerified && !(setupTransport && apNetwork != null)) {
            telemetryStatus.text = "Connexion en cours · dernière lecture affichée si disponible."
            return
        }
        telemetryBusy = true
        val requestSlot=activeReaderSlot
        val requestEpoch=readerSelectionEpoch
        io.execute {
            try {
                val reply = httpCall("GET", "/api/v1/telemetry")
                runOnUiThread {
                    if(requestSlot==activeReaderSlot && requestEpoch==readerSelectionEpoch)
                        showTelemetry(reply,newRead=true)
                }
            } catch (ex: Exception) {
                if ((remoteVpnConnection || remoteGatewayConnection) && !vpnConnected()) lanVerified = false
                runOnUiThread {
                    if(requestSlot==activeReaderSlot && requestEpoch==readerSelectionEpoch)
                        telemetryStatus.text = "Lecture JK impossible : " +
                            (ex.message ?: ex.javaClass.simpleName)
                }
            } finally {
                telemetryBusy = false
            }
        }
    }

    private fun highlightActiveTab() {
        val active = ColorStateList.valueOf(mint)
        val inactive = ColorStateList.valueOf(Color.rgb(38, 57, 77))
        for ((i,button) in listOf(tabOverview,tabExtended,tabSettings).withIndex()) {
            button.backgroundTintList = if (i == currentTab) active else inactive
            button.setTextColor(if (i == currentTab) background else normal)
        }
    }

    private fun switchDashboardTab(tab: Int) {
        if(tab!=currentTab) parameterEditingEnabled=false
        if (tab == currentTab) {
            if(tab==2 && (lastDetails==null ||
               android.os.SystemClock.elapsedRealtime()-lastDetailsFetchedAt>1800000L))
                refreshDetails()
            return
        }
        currentTab = tab
        parameterFeedback.visibility =
            if(tab==2 && lastParameterFeedback.isNotBlank()) View.VISIBLE else View.GONE
        highlightActiveTab()
        mountedTab = -1
        lastTelemetry?.let { showTelemetry(it) }
        if (tab == 2 && (lastDetails==null ||
            android.os.SystemClock.elapsedRealtime()-lastDetailsFetchedAt>1800000L))
            refreshDetails()
    }

    private fun refreshDetails() {
        if (detailsBusy || !lanVerified && !(setupTransport && apNetwork != null)) return
        detailsBusy = true
        io.execute {
            try {
                val response = httpCall("GET", "/api/v1/details")
                lastDetailsFetchedAt = android.os.SystemClock.elapsedRealtime()
                runOnUiThread {
                    lastDetails = response
                    if (currentTab == 2) lastTelemetry?.let { showTelemetry(it) }
                }
            } catch (ex: Exception) {
                lastDetailsFetchedAt = android.os.SystemClock.elapsedRealtime()
                runOnUiThread {
                    if (currentTab == 2)
                        telemetryStatus.text = "Lecture des paramètres indisponible : " +
                            (ex.message ?: ex.javaClass.simpleName)
                }
            } finally {
                detailsBusy = false
            }
        }
    }

    private fun detailForMac(mac: String): JSONObject? {
        val devices = lastDetails?.optJSONArray("devices") ?: return null
        for (i in 0 until devices.length()) {
            val item = devices.optJSONObject(i) ?: continue
            if (item.optString("mac") == mac) return item
        }
        return null
    }

    private fun editJkParameter(mac: String, param: JSONObject) {
        // The firmware's 15-second per-BMS write cooldown remains mandatory.
        // Never bypass it or allow overlapping writes from a stale UI.
        if(parameterWriteInFlight) {
            writeFeedback("Une modification est déjà en cours de vérification.")
            return
        }
        if(android.os.SystemClock.elapsedRealtime()<parameterWriteCooldownUntil) {
            writeFeedback("Temporisation de sécurité du BMS : patiente avant la prochaine modification.")
            return
        }
        if(!parameterEditingEnabled) {
            writeFeedback("Active d'abord le mode modifications dans Paramètres.")
            return
        }
        if(remoteGatewayConnection || remoteVpnConnection) {
            AlertDialog.Builder(this).setTitle("Écriture distante désactivée")
                .setMessage("Notre ESP32 VPN est exclusivement en lecture. " +
                    "Pour modifier un BMS, connecte-toi directement à l'ESP32 JK " +
                    "sur le Wi-Fi de confiance.")
                .setPositiveButton("Compris",null).show()
            return
        }
        if(!lanVerified || setupTransport) {
            writeFeedback("Connecte d'abord ton ESP32 JK sur le réseau local.")
            return
        }
        val parts=lastLanFirmwareVersion.substringBefore("-").split(".")
            .mapNotNull { it.toIntOrNull() }
        if(parts.size!=3 || parts[0]!=0 || parts[1]!=3 || parts[2]<7) {
            writeFeedback("Firmware JK 0.3.7 ou supérieur requis pour cet éditeur complet.")
            return
        }
        val key=param.optString("key")
        val label=param.optString("label",key)
        if(!param.optBoolean("editable") || key.isBlank()) return
        val risk=param.optInt("risk_level",2)
        val pilot=detailForMac(mac)?.optJSONObject("write_pilot")
        if(pilot!=null && !pilot.optBoolean("ready")) {
            writeFeedback("Modification bloquée : "+
                JkWriteHelp.reason(pilot.optString("reason")))
            return
        }
        val isBool=param.optString("type")=="bool"
        val oldValue: Any = if(isBool) param.optBoolean("value")
            else param.optDouble("value",Double.NaN)
        if(oldValue is Double && !oldValue.isFinite()) {
            writeFeedback("Valeur actuelle manquante : relis les paramètres.")
            return
        }

        fun sendWrite(newValue: Any) {
            if(parameterWriteInFlight)return
            parameterWriteInFlight=true
            val readerAtStart=activeReaderSlot
            io.execute {
                try {
                    val arm=JSONObject().put("mac",mac)
                        .put("confirmation","I_CONFIRM_ONE_JK_WRITE")
                    httpCall("POST","/api/v1/jk/write-arm",arm)
                    val req=JSONObject().put("mac",mac).put("key",key)
                        .put("value",newValue).put("expected_old",oldValue)
                        .put("confirmation","APPLY_TO_SELECTED_BMS")
                    if(risk>=2) req.put("risk_confirmation","I_ACCEPT_PROTECTION_RISK")
                    httpCall("POST","/api/v1/jk/write",req)
                    // Mirrors the server's minimum write spacing, adding a
                    // safety margin. The server independently enforces it.
                    val writeAcceptedAt=android.os.SystemClock.elapsedRealtime()
                    runOnUiThread {
                        parameterWriteCooldownUntil=JkEditRecoveryPolicy.nextAllowedAt(writeAcceptedAt)
                    }
                    writeFeedback("Commande envoyée à $label : vérification de la valeur sur le BMS…")
                    var finalState="sent_unconfirmed"
                    // Poll the ESP32 verification status without re-sending
                    // any write. Short polls reduce UI latency on fast BMS.
                    for(i in 0 until 20) {
                        Thread.sleep(750)
                        val path="/api/v1/jk/write-status?mac="+
                            java.net.URLEncoder.encode(mac,"UTF-8")
                        val status=httpCall("GET",path)
                        finalState=status.optString("state","sent_unconfirmed")
                        if(finalState !in setOf("queued","sent_unconfirmed")) break
                    }
                    if(finalState=="confirmed") {
                        writeFeedback("BMS confirmé : $label a été modifié et relu. "+
                            "Préparation de la prochaine modification…")
                    } else {
                        writeFeedback("Modification NON confirmée : "+
                            JkWriteHelp.reason(finalState)+
                            ". Contrôle la valeur sur le JK avant de recommencer.")
                    }
                    // Show the updated write pilot immediately, even if its
                    // 15-second rate limit means editing is temporarily blocked.
                    lastDetailsFetchedAt=0L
                    refreshDetails()
                    if(finalState=="confirmed") {
                        runOnUiThread {
                            val remaining=JkEditRecoveryPolicy.delayBeforeRefresh(
                                parameterWriteCooldownUntil,
                                android.os.SystemClock.elapsedRealtime())
                            uiHandler.postDelayed({
                                if(!isFinishing && activeReaderSlot==readerAtStart &&
                                   selectedBmsMac==mac && currentTab==2 &&
                                   parameterEditingEnabled && lanVerified &&
                                   !remoteVpnConnection && !remoteGatewayConnection) {
                                    // The write was ALREADY verified by a
                                    // fresh BMS settings frame. Query only the
                                    // ESP32 cache now: no extra BLE read needed.
                                    checkWriteReadinessAfterCooldown(mac,readerAtStart)
                                }
                            },remaining)
                        }
                    }
                } catch(ex:Exception) {
                    writeFeedback("Modification REFUSÉE : "+
                        JkWriteHelp.reason(ex.message ?: "erreur")+
                        ". Ne réessaie pas avant une nouvelle lecture.")
                    // A rejected write never auto-retries. Manual refresh stays
                    // available, with all firmware safety checks unchanged.
                    lastDetailsFetchedAt=0L
                    refreshDetails()
                } finally {
                    runOnUiThread { parameterWriteInFlight=false }
                }
            }
        }

        fun confirmAndSend(newValue: Any) {
            if(!isBool && (newValue !is Double || !newValue.isFinite())) {
                writeFeedback("Valeur numérique incorrecte.")
                return
            }
            if(newValue==oldValue) {
                writeFeedback("La valeur demandée est déjà celle enregistrée.")
                return
            }
            val oldText=if(isBool) if(oldValue==true)"Activé" else "Désactivé"
                else oldValue.toString()
            val newText=if(isBool) if(newValue==true)"Activé" else "Désactivé"
                else newValue.toString()
            val warning=when(risk) {
                0 -> "Réglage courant."
                1 -> "Réglage opérationnel : peut modifier le comportement du BMS."
                else -> "PARAMÈTRE DE PROTECTION CRITIQUE : un mauvais réglage peut " +
                    "désactiver une protection, endommager la batterie ou provoquer " +
                    "un incident électrique/thermique."
            }
            AlertDialog.Builder(this)
                .setTitle(if(risk>=2) "Attention : protection du BMS" else
                    "Confirmer le réglage sur un seul JK")
                .setMessage("Batterie ciblée : $mac\nParamètre : $label\n"+
                    "Valeur actuelle : $oldText\nNouvelle valeur : $newText\n\n"+
                    warning+"\n\nL'écriture reste locale, limitée à ce BMS "+
                    "et doit être confirmée par relecture.")
                .setNegativeButton("Annuler",null)
                .setPositiveButton(if(risk>=2) "Continuer" else "Confirmer") {_,_->
                    if(risk<2) {
                        sendWrite(newValue)
                    } else {
                        // A second, deliberate human confirmation is mandatory.
                        val phrase=input("Saisir PROTECTION pour confirmer")
                        AlertDialog.Builder(this)
                            .setTitle("Confirmation de sécurité")
                            .setMessage("Ne confirme que si tu connais les conséquences "+
                                "de ce changement sur les protections de la batterie. "+
                                "Saisis PROTECTION pour autoriser cette écriture unique.")
                            .setView(phrase)
                            .setNegativeButton("Annuler",null)
                            .setPositiveButton("Autoriser") {_,_->
                                if(phrase.text.toString().trim()!="PROTECTION")
                                    writeFeedback("Modification critique annulée : "+
                                        "confirmation PROTECTION absente.")
                                else sendWrite(newValue)
                            }.show()
                    }
                }.show()
        }
        if(isBool) {
            AlertDialog.Builder(this).setTitle(label)
                .setItems(arrayOf("Activer","Désactiver")) {_,choice ->
                    confirmAndSend(choice==0)
                }.setNegativeButton("Annuler",null).show()
        } else {
            val field=input("Nouvelle valeur")
            field.inputType=android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            AlertDialog.Builder(this).setTitle(label)
                .setMessage("Valeur actuelle : $oldValue ${param.optString("unit")}")
                .setView(field).setNegativeButton("Annuler",null)
                .setPositiveButton("Vérifier"){_,_->
                    val next=field.text.toString().trim().replace(',','.').toDoubleOrNull()
                    if(next==null || !next.isFinite())
                        writeFeedback("Nouvelle valeur incorrecte.")
                    else confirmAndSend(next)
                }.show()
        }
    }


    // A single encrypted Android Keystore backs the existing pairing codes.
    // Each reader has independent code, identity, LAN address and BMS registry.
    // The private VPN endpoint/token remain common to all three readers.
    private val readerKeys get() = listOf("pairing_code_cipher","device_id",
        "setup_ssid","local_ip","paired_bms_json","known_jk_macs")
    private fun slotKey(slot:Int,key:String) = "reader_${slot}_${key}"

    private fun refreshReaderSelector() {
        readerSelector.removeAllViews()
        for(slot in 1..3) {
            val active=slot==activeReaderSlot
            val item=action("ESP $slot") { switchReaderSlot(slot) }
            item.textSize=13f
            item.backgroundTintList=ColorStateList.valueOf(
                if(active) mint else Color.rgb(38,57,77))
            item.setTextColor(if(active) background else normal)
            readerSelector.addView(item,LinearLayout.LayoutParams(0,d(46),1f).apply {
                rightMargin=d(4)
            })
        }
        val configured=prefs.getString("device_id","")?.isNotBlank()==true
        readerHeader.text="ESP $activeReaderSlot sélectionné — "+
            (if(configured)"lecteur déjà associé" else "lecteur à associer")+
            ". Toutes les opérations ci-dessous concernent cet ESP32."
        if(::physicalRecoverBtn.isInitialized)
            physicalRecoverBtn.text="2 · Associer ESP $activeReaderSlot avec BOOT"
        if(::pairingStatus.isInitialized)
            pairingStatus.text="ESP $activeReaderSlot sélectionné. "+
                (if(configured)
                    "Tu peux retrouver ce lecteur ou relancer BOOT."
                 else
                    "Recherche son réseau SmartJK-Setup, puis associe-le avec BOOT.")
    }

    private fun switchReaderSlot(target:Int) {
        if(target !in 1..3 || target==activeReaderSlot) return
        if(setupTransport) {
            pairingState("Une association BOOT est active. "+
                "Termine-la ou annule avant de changer de lecteur.")
            return
        }
        if(lanConnectionPending) {
            // A previously selected reader may still have an automatic
            // connect in progress. One tap must be enough to select ESP 2:
            // queue the switch instead of silently rejecting the request.
            pendingReaderTarget=target
            pairingState("ESP $target demandé. La sélection sera appliquée "+
                "automatiquement après la connexion en cours.")
            uiHandler.postDelayed(object:Runnable {
                var checks=0
                override fun run() {
                    if(isFinishing || pendingReaderTarget!=target)return
                    if(lanConnectionPending) {
                        if(++checks<45) uiHandler.postDelayed(this,400)
                        else {
                            pendingReaderTarget=null
                            pairingState("Connexion précédente bloquée. "+
                                "Réessaie ESP $target après avoir vérifié le Wi-Fi.")
                        }
                    } else {
                        pendingReaderTarget=null
                        switchReaderSlot(target)
                    }
                }
            },400)
            return
        }
        pendingReaderTarget=null
        val editor=prefs.edit()
        // Snapshot the active reader; slot 1 imports legacy APK preferences.
        for(key in readerKeys)
            editor.putString(slotKey(activeReaderSlot,key),prefs.getString(key,"") ?: "")
        // Restore the chosen profile before starting new network operations.
        for(key in readerKeys) {
            val name=slotKey(target,key)
            if(prefs.contains(name)) editor.putString(key,prefs.getString(name,"") ?: "")
            else editor.remove(key)
        }
        editor.putInt("active_reader_slot",target).commit()
        activeReaderSlot=target
        readerSelectionEpoch++
        lanVerified=false
        remoteGatewayConnection=false
        remoteVpnConnection=false
        localHost=prefs.getString("local_ip","") ?: ""
        activeSetupCode=storedCode()
        selectedDeviceId=prefs.getString("device_id","") ?: ""
        releaseConnection()
        registeredBms.clear()
        chosen.clear()
        lastScannedBms=emptyList()
        scanPanel.removeAllViews()
        val focus=pendingBmsNavigationMac.takeIf {
            fleetOwnerByMac[it]==target
        }.orEmpty()
        pendingBmsNavigationMac=""
        selectedBmsMac=focus
        parameterEditingEnabled=false
        lastTelemetry=null
        lastDetails=null
        lastDetailsFetchedAt=0L
        dashboardMacs=emptyList()
        mountedTab=-1
        telemetryPanel.removeAllViews()
        status.text="ESP32 JK $target sélectionné. Connecte cette passerelle."
        telemetryStatus.text="ESP $target · récupération des dernières données…"
        // Render the existing 15-second memory cache immediately instead of
        // waiting for the /info → /bms → /telemetry handshake.
        fleetCache[target]?.let { showTelemetry(it.second) }
        lanIp.setText(localHost)
        apName.setText(prefs.getString("setup_ssid","") ?: "")
        setupCode.text.clear()
        setupCode.hint="Code ESP $target (vide si mémorisé)"
        if(::accessCodeNew.isInitialized)accessCodeNew.text.clear()
        refreshReaderSelector()
        if(selectedDeviceId.isNotBlank() && storedCode().isNotBlank()) {
            when(selectedRoute()) {
                JkTransportPolicy.Route.GATEWAY_VPN -> connectGatewayRemote()
                JkTransportPolicy.Route.LOCAL_WIFI -> connectHomeLan(false)
                else -> state("ESP32 $target : configure ou choisis le mode de connexion.")
            }
        }
        // Give the selected reader an independent authenticated GET while the
        // existing slow identity handshake continues. No extra BLE operation.
        warmReaderSnapshots(listOf(target))
        renderFleetOverview()
    }

    private fun displayBmsName(mac:String,default:String):String {
        val custom=prefs.getString("bms_name_"+mac.replace(":",""),"") ?: ""
        return custom.ifBlank { default }.take(32)
    }
    private fun renameBms(mac:String) {
        val field=input("Nom du BMS (ex. Batterie solaire 1)")
        field.setText(prefs.getString("bms_name_"+mac.replace(":",""),"") ?: "")
        AlertDialog.Builder(this)
            .setTitle("Renommer la batterie")
            .setMessage("Ce nom est conservé sur ce téléphone. Aucun réglage du BMS n'est modifié.")
            .setView(field)
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Enregistrer") { _,_ ->
                val name=field.text.toString().trim().take(32)
                prefs.edit().putString("bms_name_"+mac.replace(":",""),name).apply()
                renderBmsRegistry()
                mountedTab=-1
                lastTelemetry?.let { showTelemetry(it) }
            }.show()
    }


    // A read-only BMS settings query is queued only when a user explicitly
    // enables the edit interface. The strict 60-second write freshness guard
    // remains enforced by ESP32; it is never weakened to allow stale writes.

    // The firmware itself rejects a second write for 15s. After that limit
    // the verified post-write settings frame remains fresh (<60s), so another
    // 0x96 BLE transaction is unnecessary. Checking the local cached pilot
    // immediately avoids the long, repeated re-read cycle in v0.3.14.
    private fun checkWriteReadinessAfterCooldown(mac:String,reader:Int) {
        if(!parameterEditingEnabled || selectedBmsMac!=mac ||
           reader!=activeReaderSlot || !lanVerified || setupTransport ||
           remoteVpnConnection || remoteGatewayConnection)return
        settingsWorker.execute {
            try {
                var latest:JSONObject?=null
                var ready=false
                var reason=""
                for(attempt in 0 until 7) {
                    val details=httpCall("GET","/api/v1/details")
                    val devices=details.optJSONArray("devices")
                    val match=(0 until (devices?.length() ?: 0))
                        .mapNotNull { devices?.optJSONObject(it) }
                        .firstOrNull { it.optString("mac")==mac }
                    val pilot=match?.optJSONObject("write_pilot")
                    latest=details
                    ready=pilot?.optBoolean("ready")==true
                    reason=pilot?.optString("reason","") ?: ""
                    if(ready || reason=="stale_or_missing_settings" ||
                       reason=="bms_alarm_active" || reason=="bms_not_live")break
                    Thread.sleep(450)
                }
                val result=latest
                runOnUiThread {
                    if(!isFinishing && reader==activeReaderSlot &&
                       selectedBmsMac==mac && parameterEditingEnabled &&
                       currentTab==2 && result!=null) {
                        lastDetails=result
                        lastDetailsFetchedAt=android.os.SystemClock.elapsedRealtime()
                        lastTelemetry?.let { showTelemetry(it) }
                        when {
                            ready -> writeFeedback("BMS prêt : tu peux modifier un autre paramètre.")
                            reason=="stale_or_missing_settings" ->
                                requestFreshSettingsForEditing()
                            else -> writeFeedback("Nouvelle modification encore bloquée : "+
                                JkWriteHelp.reason(reason)+".")
                        }
                    }
                }
            } catch(ex:Exception) {
                runOnUiThread {
                    if(reader==activeReaderSlot && selectedBmsMac==mac &&
                       parameterEditingEnabled && !isFinishing)
                        writeFeedback("Vérification après modification indisponible : "+
                            (ex.message ?: "connexion locale à vérifier")+
                            ". Aucun réglage n'a été renvoyé.")
                }
            }
        }
    }

    private fun requestFreshSettingsForEditing() {
        if(!parameterEditingEnabled || !lanVerified || remoteGatewayConnection ||
           remoteVpnConnection || setupTransport || selectedBmsMac.isBlank())return
        if(settingsRefreshBusy) {
            writeFeedback("Actualisation des réglages déjà en cours.")
            return
        }
        val mac=selectedBmsMac
        val reader=activeReaderSlot
        settingsRefreshBusy=true
        lastLocalSettingsRefreshAt=android.os.SystemClock.elapsedRealtime()
        writeFeedback("Relecture des paramètres du BMS en cours…")
        // A separate worker ensures temperature/SOC telemetry remains responsive.
        // GET /details only reads the ESP32's cache, NOT an extra BLE request.
        settingsWorker.execute {
            try {
                httpCall("POST","/api/v1/jk/settings-refresh",
                    JSONObject().put("mac",mac))
                var latest:JSONObject?=null
                var ready=false
                for(attempt in 0 until 8) {
                    Thread.sleep(1100)
                    val details=httpCall("GET","/api/v1/details")
                    val devices=details.optJSONArray("devices")
                    val match=(0 until (devices?.length() ?: 0))
                        .mapNotNull { devices?.optJSONObject(it) }
                        .firstOrNull { it.optString("mac")==mac }
                    latest=details
                    ready=match?.optJSONObject("write_pilot")
                        ?.optBoolean("ready")==true
                    if(ready)break
                }
                val result=latest
                runOnUiThread {
                    if(reader==activeReaderSlot && parameterEditingEnabled &&
                       selectedBmsMac==mac && currentTab==2 && result!=null) {
                        lastDetails=result
                        lastDetailsFetchedAt=android.os.SystemClock.elapsedRealtime()
                        lastTelemetry?.let { showTelemetry(it) }
                        if(ready)
                            writeFeedback("Réglages à jour. Tu peux modifier un autre paramètre.")
                        else
                            writeFeedback("Le BMS n'autorise pas encore une nouvelle modification. "+
                                "Utilise « Actualiser les réglages » pour réessayer.")
                    }
                }
            } catch(ex:Exception) {
                runOnUiThread {
                    if(reader==activeReaderSlot && selectedBmsMac==mac &&
                       parameterEditingEnabled) {
                        writeFeedback("Relecture indisponible : "+
                            (ex.message ?: "vérifie la connexion locale")+
                            ". Aucun nouveau réglage n'a été écrit.")
                    }
                }
            } finally {
                runOnUiThread { settingsRefreshBusy=false }
            }
        }
    }

    private fun toggleParameterEditMode() {
        if(parameterEditingEnabled) {
            parameterEditingEnabled=false
            mountedTab=-1
            lastTelemetry?.let { showTelemetry(it) }
            return
        }
        if(remoteGatewayConnection || remoteVpnConnection || !lanVerified || setupTransport) {
            writeFeedback("Les modifications exigent une connexion locale directe au lecteur JK.")
            return
        }
        if(selectedBmsMac.isBlank()) {
            writeFeedback("Sélectionne un BMS avant d'activer les modifications.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Attention : modifications des paramètres")
            .setMessage("Tu entres dans les modifications de paramètres du BMS. Des valeurs incorrectes peuvent endommager la batterie ou neutraliser des protections. Chaque changement demandera une confirmation individuelle ; les protections critiques exigent une confirmation supplémentaire. Aucune modification automatique.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Activer le mode modification") { _,_ ->
                parameterEditingEnabled=true
                mountedTab=-1
                lastTelemetry?.let { showTelemetry(it) }
                requestFreshSettingsForEditing()
            }.show()
    }

    private fun navigateToBms(mac:String) {
        val slot=fleetOwnerByMac[mac] ?: activeReaderSlot
        if(slot!=activeReaderSlot) {
            pendingBmsNavigationMac=mac
            switchReaderSlot(slot)
            // Queued slot changes will select the battery once completed.
            return
        }
        pendingBmsNavigationMac=""
        selectBms(mac)
    }

    private fun selectBms(mac: String) {
        parameterEditingEnabled=false
        selectedBmsMac = mac
        mountedBmsMac = ""
        mountedTab = -1
        // Overview is strictly the Accueil page, never a permanent
        // header above the detail of a selected BMS.
        if(::fleetPanel.isInitialized)
            fleetPanel.visibility=if(JkDashboardVisibility.showFleet(mac))View.VISIBLE else View.GONE
        lastTelemetry?.let { showTelemetry(it) }
        renderFleetOverview()
        if (currentTab == 2 && mac.isNotBlank()) refreshDetails()
    }

    private fun renderBmsTabs(tabs:List<JkBmsTab>) {
        bmsTabRow.removeAllViews()
        bmsTabStrip.visibility=View.VISIBLE
        fun button(caption:String,mac:String) {
            val active=selectedBmsMac==mac
            val item=action(caption) { navigateToBms(mac) }
            item.textSize=12f
            item.backgroundTintList=ColorStateList.valueOf(
                if(active)mint else Color.rgb(38,57,77))
            item.setTextColor(if(active)background else normal)
            bmsTabRow.addView(item,LinearLayout.LayoutParams(d(103),d(43)).apply{
                rightMargin=d(6)
            })
        }
        button("Accueil","")
        for(tab in tabs)button(tab.name,tab.mac)
    }


    // Multi-reader overview: never sum old samples or silently ignore a
    // configured BMS. Read each ESP32's independent HTTP cache, not extra BLE.
    private fun registeredMacs(slot:Int):List<String> {
        val key=if(slot==activeReaderSlot) "paired_bms_json"
                else slotKey(slot,"paired_bms_json")
        return try {
            val array=JSONArray(prefs.getString(key,"[]") ?: "[]")
            (0 until array.length()).mapNotNull { i ->
                array.optString(i).takeIf { it.isNotBlank() }
            }.distinct()
        } catch (_:Exception) { emptyList() }
    }

    private fun renderFleetOverview() {
        if(!::fleetOverview.isInitialized) return
        fleetPanel.visibility=if(JkDashboardVisibility.showFleet(selectedBmsMac))View.VISIBLE else View.GONE
        val all=JSONArray()
        val now=android.os.SystemClock.elapsedRealtime()
        fleetOwnerByMac.clear()
        fleetNumberByMac.clear()
        for(slot in 1..3) {
            val saved=registeredMacs(slot)
            val cached=fleetCache[slot]
            val fresh=cached!=null && now-cached.first<=15000L
            val devices=cached?.second?.optJSONArray("devices")
            val byMac=mutableMapOf<String,JSONObject>()
            if(devices!=null) for(i in 0 until devices.length()) {
                val item=devices.optJSONObject(i) ?: continue
                val mac=item.optString("mac")
                if(mac.isNotBlank())byMac[mac]=item
            }
            val addresses=(saved+byMac.keys).distinct()
            for((index,mac) in addresses.withIndex()) {
                val record=byMac[mac]
                val display=if(fresh && record!=null)
                    JSONObject(record.toString()) else JSONObject().put("mac",mac)
                display.put("reader_slot",slot)
                if(!fresh) {
                    display.put("available",false)
                    display.remove("values")
                }
                all.put(display)
                fleetOwnerByMac[mac]=slot
                fleetNumberByMac[mac]=index+1
            }
        }
        fleetConfiguredCount=all.length()
        fleetAvailableCount=(0 until all.length()).count { all.optJSONObject(it)?.optBoolean("available")==true }
        updatePremiumJk(all)
        val tabs=JkBmsTabOrder.sorted((0 until all.length()).mapNotNull { index ->
            val bms=all.optJSONObject(index) ?: return@mapNotNull null
            val mac=bms.optString("mac")
            if(mac.isBlank())null else JkBmsTab(mac,
                displayBmsName(mac,"Batt-${index+1}"),bms.optInt("reader_slot",1))
        })
        val tabsSignature=tabs.joinToString("|") { "${it.mac}:${it.name}:${it.reader}" }+
            "#${selectedBmsMac}"
        if(tabsSignature!=fleetTabSignature) {
            fleetTabSignature=tabsSignature
            renderBmsTabs(tabs)
        }
        // Keep fleet health counters current for Debug, but don't recreate
        // hidden global views on every 2.5s telemetry tick of a selected BMS.
        if(!JkDashboardVisibility.showFleet(selectedBmsMac))return
        fleetOverview.bind(all,
            { mac -> navigateToBms(mac) },
            { mac,index -> displayBmsName(mac,"Batt-${index+1}") })
    }

    private fun slotPairingCode(slot:Int):String {
        if(slot==activeReaderSlot) return storedCode()
        val encoded=prefs.getString(slotKey(slot,"pairing_code_cipher"),"") ?: ""
        if(encoded.isBlank())return ""
        return try {
            val data=Base64.decode(encoded,Base64.NO_WRAP)
            if(data.size<29) "" else {
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,encryptionKey(),
                    GCMParameterSpec(128,data.copyOfRange(0,12)))
                String(cipher.doFinal(data.copyOfRange(12,data.size)),Charsets.UTF_8)
            }
        }catch (_:Exception){""}
    }

    private data class FastReaderRequest(
        val slot:Int,
        val url:String,
        val header:String,
        val credential:String,
        val pairedMacs:List<String>,
        val startedAtMs:Long
    )

    /** Collect a per-reader credential snapshot *on the UI thread*, before any
     * switch mutates the legacy active-reader preference keys. */
    private fun makeFastReaderRequest(slot:Int):FastReaderRequest? {
        if(slot !in 1..3 || setupTransport)return null
        val macs=JkBmsRegistry.normalized(registeredMacs(slot))
        if(macs.isEmpty())return null
        val vpn=vpnConnected()
        val preference=prefs.getString("connection_preference","auto") ?: "auto"
        val localIp=if(slot==activeReaderSlot)
            prefs.getString("local_ip","") ?: ""
            else prefs.getString(slotKey(slot,"local_ip"),"") ?: ""
        val localCode=slotPairingCode(slot)
        val localOk=allowedLocalIp(localIp) && localCode.length in 8..32
        val gatewayIp=prefs.getString("remote_gateway_ip","") ?: ""
        val gatewayToken=storedGatewayToken()
        val gatewayOk=allowedTailnetIp(gatewayIp) && validGatewayToken(gatewayToken)
        val route=JkTransportPolicy.select(vpn,preference,
            prefs.getBoolean("remote_gateway_enabled",false),gatewayOk,localOk)
        val endpoint:String
        val header:String
        val credential:String
        when(route) {
            JkTransportPolicy.Route.GATEWAY_VPN -> {
                if(!vpn || !gatewayOk)return null
                val path=JkTransportPolicy.vpnReadPath(slot,"/api/v1/telemetry")
                    ?: return null
                endpoint="http://$gatewayIp:18767$path"
                header="X-Gateway-Token"
                credential=gatewayToken
            }
            JkTransportPolicy.Route.LOCAL_WIFI -> {
                if(!localOk)return null
                endpoint="http://$localIp/api/v1/telemetry"
                header="X-Setup-Code"
                credential=localCode
            }
            else -> return null
        }
        return FastReaderRequest(slot,endpoint,header,credential,macs,
            android.os.SystemClock.elapsedRealtime())
    }

    /** Authentication, device identity and fresh-data rules stay identical to
     * the regular reader. No settings endpoint, no BLE request, no write. */
    private fun fastReadOnce(request:FastReaderRequest):JSONObject? {
        val conn=URL(request.url).openConnection() as HttpURLConnection
        return try {
            conn.instanceFollowRedirects=false
            conn.connectTimeout=1800
            conn.readTimeout=2800
            conn.requestMethod="GET"
            conn.setRequestProperty(request.header,request.credential)
            if(conn.responseCode!=200)return null
            val json=JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if(json.optString("source")!="jk02-ffe1-read-only")return null
            val devices=json.optJSONArray("devices") ?: return null
            val returned=(0 until devices.length()).mapNotNull { index ->
                devices.optJSONObject(index)?.optString("mac")?.takeIf { it.isNotBlank() }
            }
            if(!JkFastReaderPolicy.recognized(request.pairedMacs,returned))return null
            json
        } finally { conn.disconnect() }
    }

    /** Parallel GETs eliminate the cold-start delay caused by the /info and
     * /bms handshake. No historical samples are saved to disk. */
    private fun warmReaderSnapshots(requested:List<Int> = (1..3).toList()) {
        if(setupTransport)return
        val now=android.os.SystemClock.elapsedRealtime()
        if(requested.size>1 && lastWarmStartAt>0L &&
            now-lastWarmStartAt<10_000L)return
        if(requested.size>1)lastWarmStartAt=now
        for(slot in requested.distinct()) {
            if(slot in warmReadersPending)continue
            val request=makeFastReaderRequest(slot) ?:continue
            warmReadersPending.add(slot)
            readerWarmWorker.execute {
                val result=try { fastReadOnce(request) } catch(_:Exception){null}
                runOnUiThread {
                    warmReadersPending.remove(slot)
                    if(isFinishing || isDestroyed || setupTransport || result==null)return@runOnUiThread
                    // Do not show a reply from a profile replaced during pairing.
                    if(JkBmsRegistry.normalized(registeredMacs(slot))!=request.pairedMacs)return@runOnUiThread
                    val newer=fleetCache[slot]?.first
                    if(newer!=null && newer>request.startedAtMs)return@runOnUiThread
                    fleetCache[slot]=android.os.SystemClock.elapsedRealtime() to result
                    if(slot==activeReaderSlot) {
                        showTelemetry(result,newRead=true)
                        if(lanConnectionPending)
                            telemetryStatus.text="Mesures JK disponibles · vérification de l'ESP32 en cours."
                    } else renderFleetOverview()
                }
            }
        }
    }

    private fun pollOtherFleetReader() {
        if(fleetBusy || setupTransport)return
        val slots=(1..3).filter { it!=activeReaderSlot &&
            it !in warmReadersPending && registeredMacs(it).isNotEmpty() }
        if(slots.isEmpty())return
        val slot=slots[nextFleetSlot++ % slots.size]
        val request=makeFastReaderRequest(slot) ?:return
        fleetBusy=true
        fleetWorker.execute {
            val result=try {fastReadOnce(request)}catch(_:Exception){null}
            runOnUiThread {
                // A slow reply must not change the currently displayed BMS.
                if(!isFinishing && !isDestroyed && result!=null &&
                    JkBmsRegistry.normalized(registeredMacs(slot))==request.pairedMacs) {
                    val newer=fleetCache[slot]?.first
                    if(newer==null || newer<=request.startedAtMs) {
                        fleetCache[slot]=android.os.SystemClock.elapsedRealtime() to result
                        renderFleetOverview()
                    }
                }
                fleetBusy=false
            }
        }
    }

    private fun showTelemetry(rawResponse: JSONObject,newRead:Boolean=false) {
        if(::dashboardDebugStatus.isInitialized)dashboardDebugStatus.text=status.text
        lastTelemetry=rawResponse
        val now=android.os.SystemClock.elapsedRealtime()
        if(newRead)fleetCache[activeReaderSlot]=now to rawResponse
        val cache=fleetCache[activeReaderSlot]
        val cacheAge=if(cache!=null)now-cache.first else Long.MAX_VALUE
        val fresh=cache!=null && JkFastReaderPolicy.isFresh(cache.first,now)
        // The last read may be shown instantly; after 15 seconds hide values
        // and alarms until a real GET succeeds. No stale value is "live".
        val response=if(fresh)rawResponse else JSONObject(rawResponse.toString()).apply {
            optJSONArray("devices")?.let { list ->
                for(i in 0 until list.length())list.optJSONObject(i)?.apply {
                    put("available",false)
                    remove("values")
                }
            }
        }
        val devices = response.optJSONArray("devices")
        if (devices == null || devices.length() == 0) {
            selectedBmsMac=""
            renderFleetOverview()
            telemetryStatus.text = if (devices == null)
                "Ce firmware ne prend pas en charge les mesures JK."
            else "Aucun JK enregistré. Ouvre « Configuration et association »."
            telemetryPanel.removeAllViews()
            dashboardCards.clear(); advancedCards.clear()
            dashboardMacs = emptyList()
            return
        }
        val macs = (0 until devices.length()).mapNotNull { i ->
            devices.optJSONObject(i)?.optString("mac")?.takeIf { it.isNotBlank() }
        }
        if (selectedBmsMac.isNotBlank() && selectedBmsMac !in macs)
            selectedBmsMac = ""
        // Always provide Accueil, even for a single BMS.
        val global = JkDashboardVisibility.showFleet(selectedBmsMac)
        renderFleetOverview()
        parameterFeedback.visibility=if(global)View.GONE else
            if(currentTab==2 && lastParameterFeedback.isNotBlank())View.VISIBLE else View.GONE
        val rerender = macs != dashboardMacs ||
            mountedTab != currentTab || mountedBmsMac != selectedBmsMac
        if (rerender) {
            dashboardMacs = macs
            mountedTab = currentTab
            mountedBmsMac = selectedBmsMac
            dashboardCards.clear(); advancedCards.clear()
            globalOverview = null
            telemetryPanel.removeAllViews()
            detailsTabRow.visibility = if(global) View.GONE else View.VISIBLE
            if(!global) {
                val mac = selectedBmsMac
                if(currentTab == 0) {
                    val card = BatteryDashboardCard(this)
                    dashboardCards[mac] = card
                    telemetryPanel.addView(card,LinearLayout.LayoutParams(-1,-2))
                } else {
                    val card = BatteryAdvancedCard(this)
                    advancedCards[mac] = card
                    telemetryPanel.addView(card,LinearLayout.LayoutParams(-1,-2))
                }
            }
        }
        val liveCount=(0 until devices.length()).count { devices.optJSONObject(it)?.optBoolean("available")==true }
        if(!global) {
            for(i in 0 until devices.length()){
                val item=devices.optJSONObject(i) ?: continue
                val mac=item.optString("mac")
                if(mac != selectedBmsMac) continue
                when(currentTab) {
                    0 -> dashboardCards[mac]?.bind(i,item)
                    1 -> advancedCards[mac]?.measurements(i,item,debugEnabled)
                    2 -> advancedCards[mac]?.settings(
                        i,detailForMac(mac),item,
                        allowEdits=parameterEditingEnabled,
                        showDebug=debugEnabled,
                        onModeToggle={ toggleParameterEditMode() },
                        onRefresh={ requestFreshSettingsForEditing() },
                        onEdit={ param -> editJkParameter(mac,param) }
                    )
                }
            }
        }
        // Expose BLE setup diagnostics even when the connection never reaches
        // the first valid measurement. No MACs or access codes in this label.
        val offlineDiagnostics = (0 until devices.length()).mapNotNull { i ->
            val item = devices.optJSONObject(i) ?: return@mapNotNull null
            if (item.optBoolean("available")) return@mapNotNull null
            val attempts = item.optInt("ble_connect_attempts", 0)
            val scans = item.optInt("ble_scan_attempts", 0)
            val reason = readerStatus(item.optString("state"))
            val err = item.optInt("ble_last_error", 0)
            val busy = item.optInt("ble_gap_busy_count", 0)
            val cancelled = item.optInt("ble_gap_cancel_count", 0)
            val stuck = item.optInt("ble_gap_still_busy_count", 0)
            val before = item.optBoolean("ble_gap_active_before", false)
            val after = item.optBoolean("ble_gap_active_after", false)
            val duration = item.optInt("ble_connect_duration_ms", 0)
            val scanWasActive = item.optBoolean("ble_scan_active_before", false)
            "BMS ${i + 1} : $reason · scans $scans · connexions tentées $attempts" +
                (if (err == 2) " · BLE 2 (connexion NimBLE déjà en cours)"
                 else if (err != 0) " · BLE $err" else "") +
                (if(busy>0) " · attentes $busy" else "") +
                (if(cancelled>0) " · libérations $cancelled" else "") +
                (if(stuck>0) " · blocages $stuck" else "") +
                (if(attempts>0) " · essai ${duration} ms" else "") +
                (if(before || after) " · GAP ${if(before)1 else 0}/${if(after)1 else 0}" else "") +
                (if(scanWasActive) " · scan encore actif" else "")
        }
        // Diagnose disconnections even after the JK successfully reconnects.
        // Counts are since the ESP32 last booted, never a lifetime statistic.
        val linkDiagnostics = (0 until devices.length()).mapNotNull { i ->
            val item=devices.optJSONObject(i) ?: return@mapNotNull null
            val drops=item.optInt("disconnections",0)
            if(drops==0) return@mapNotNull null
            val radio=item.optInt("ble_radio_disconnections",0)
            val local=item.optInt("ble_intentional_disconnections",0)
            val reason=item.optInt("ble_last_disconnect_reason",-1)
            val reasonLabel=when(reason) {
                8 -> "supervision BLE expirée"
                19 -> "connexion terminée côté BMS"
                22 -> "connexion terminée localement"
                -1 -> "motif radio non capturé"
                else -> "code GAP $reason"
            }
            val rssi=item.optInt("ble_rssi_dbm",0)
            val timeout=item.optInt("ble_timeout_ms",0)
            val since=item.optInt("ble_connected_seconds",-1)
            "BMS ${i+1} : $drops coupures (radio $radio, gestionnaire $local)" +
                " · $reasonLabel" +
                (if(rssi!=0) " · RSSI $rssi dBm" else "") +
                (if(timeout>0) " · timeout $timeout ms" else "") +
                (if(since>=0) " · connecté depuis ${since}s" else "")
        }
        val wifiDrops=response.optInt("wifi_disconnect_count",0)
        val minHeap=response.optInt("min_free_heap_bytes",0)
        val wifiNote=if(wifiDrops>0) "Wi-Fi ESP32 : $wifiDrops coupure(s) depuis démarrage" else ""
        val heapNote=if(minHeap>0) "Mémoire libre minimale : ${minHeap/1024} Ko" else ""
        val sectionTitle = if (global) "Synthèse des batteries" else
            "BMS ${macs.indexOf(selectedBmsMac) + 1} · " +
                when (currentTab) {
                    0 -> "Batterie"
                    1 -> "Détails"
                    else -> "Paramètres"
                }
        val diagnostics=offlineDiagnostics + linkDiagnostics +
            listOf(wifiNote,heapNote).filter { it.isNotBlank() }
        val ageLabel=when {
            newRead -> ""
            fresh -> " · dernière lecture il y a ${(cacheAge/1000).coerceAtLeast(0)} s (actualisation…)"
            else -> " · mesures périmées, reconnexion…"
        }
        telemetryStatus.text = "$fleetAvailableCount/$fleetConfiguredCount BMS en ligne (tous lecteurs) · ESP $activeReaderSlot : $liveCount/${devices.length()} · $sectionTitle" +
            ageLabel + if (!debugEnabled || diagnostics.isEmpty()) "" else "\n" + diagnostics.joinToString("\n")
    }


    // The new energy home reads the already-cached JK samples. It does not
    // trigger extra BLE scans or alter any configuration of existing readers.
    private fun updatePremiumJk(all:JSONArray) {
        val devices=(0 until all.length()).mapNotNull { all.optJSONObject(it) }
        val live=devices.filter { it.optBoolean("available") &&
            it.optJSONObject("values")!=null }
        val complete=devices.isNotEmpty() && live.size==devices.size
        val readings=live.mapNotNull { it.optJSONObject("values") }
        val soc:Double?=if(complete && readings.all {
            it.has("soc_pct") && it.optDouble("soc_pct",Double.NaN).isFinite() &&
                it.optDouble("soc_pct") in 0.0..100.0
        }) {
            val caps=readings.map { it.optDouble("nominal_capacity_ah",0.0) }
            if(caps.all { it.isFinite() && it>0 }) {
                val sum=caps.sum()
                readings.indices.sumOf { readings[it].optDouble("soc_pct")*caps[it] }/sum
            } else readings.map { it.optDouble("soc_pct") }.average()
        } else null
        val power:Double?=if(complete && readings.all {
            it.has("power_w") && it.optDouble("power_w",Double.NaN).isFinite()
        }) readings.sumOf { it.optDouble("power_w") } else null
        val display=JSONArray()
        devices.forEachIndexed { index,item ->
            val v=if(item.optBoolean("available"))item.optJSONObject("values") else null
            val mac=item.optString("mac")
            val entry=JSONObject().put("name",displayBmsName(mac,"Batt-"+(index+1)))
                .put("available",v!=null)
            if(v!=null) {
                if(v.has("soc_pct") && v.optDouble("soc_pct",Double.NaN).isFinite())
                    entry.put("soc",v.optDouble("soc_pct"))
                if(v.has("voltage_v") && v.optDouble("voltage_v",Double.NaN).isFinite())
                    entry.put("voltage",v.optDouble("voltage_v"))
                if(v.has("power_w") && v.optDouble("power_w",Double.NaN).isFinite())
                    entry.put("powerW",v.optDouble("power_w"))
            }
            display.put(entry)
        }
        premiumJk=JSONObject()
            .put("total",devices.size)
            .put("online",live.size)
            .put("complete",complete)
            .put("devices",display)
            .put("soc",soc ?: JSONObject.NULL)
            .put("powerW",power ?: JSONObject.NULL)
        publishPremiumDashboard()
    }

    private fun saveAuxiliaryCode(key:String,plaintext:String) {
        require(plaintext.length in 8..32)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,encryptionKey())
        val encrypted=cipher.iv+cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key,Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply()
    }

    private fun auxiliaryCode(key:String):String {
        val encoded=prefs.getString(key,"") ?: ""
        if(encoded.isBlank())return ""
        return try {
            val combined=Base64.decode(encoded,Base64.NO_WRAP)
            if(combined.size<29) "" else {
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,encryptionKey(),
                    GCMParameterSpec(128,combined.copyOfRange(0,12)))
                String(cipher.doFinal(combined.copyOfRange(12,combined.size)),Charsets.UTF_8)
            }
        }catch(_:Exception){""}
    }

    private fun premiumVoltronicSnapshot(raw:JSONObject):JSONObject {
        val obj=JSONObject().put("configured",true).put("name","Voltronic / Axpert")
        val values=raw.optJSONObject("values")
        val age=raw.optLong("age_ms",-1L)
        val fresh=raw.optBoolean("available") &&
            age in 0L..10000L && values!=null
        obj.put("fresh",fresh)
        if(!fresh || values==null)return obj.put("pvDetail","Mesures non disponibles")
        fun value(key:String):Double? {
            if(!values.has(key)||values.isNull(key))return null
            return values.optDouble(key,Double.NaN).takeIf { it.isFinite() }
        }
        value("output_w")?.takeIf { it>=0 && it<=30000 }?.let { obj.put("loadW",it) }
        val pv1Voltage=value("pv1_voltage_v")
        val pv1Current=value("pv1_current_a")
        val pv2Power=value("pv2_power_w")
        val pv1Power=if(pv1Voltage!=null && pv1Current!=null &&
            pv1Voltage in 0.0..900.0 && pv1Current in 0.0..100.0)
            pv1Voltage*pv1Current else null
        if(pv1Power!=null && pv2Power!=null &&
            pv2Power in 0.0..30000.0) {
            obj.put("pvPowerW",pv1Power+pv2Power)
            obj.put("pvDetail","PV1 estimé + PV2 mesuré")
        } else {
            obj.put("pvDetail",
                if(pv1Power!=null)"PV1 seul · PV2 indisponible" else
                    "Production PV indisponible")
        }
        value("grid_power_native_w")?.takeIf { kotlin.math.abs(it)<30000 }?.let {
            obj.put("gridPowerW",it)
            obj.put("gridDetail","Puissance native · sens à valider")
        }
        // No grid arrows until the native sign convention is measured.
        obj.put("gridDirectionVerified",false)
        return obj
    }

    private fun publishPremiumDashboard() {
        if(!::premiumDashboard.isInitialized)return
        val route=when {
            remoteGatewayConnection && lanVerified -> "VPN Tailscale"
            vpnConnected() -> "VPN · connexion en cours"
            lanVerified -> "Wi-Fi local"
            else -> "Hors ligne"
        }
        premiumInverter=voltronicFleet.snapshotFor(1)
        // Only native, fresh GET snapshots can update the persisted profile.
        // No backup import is ever sent to the inverter.
        for(slot in 1..3)
            voltronicProfiles.observe(slot,voltronicFleet.snapshotFor(slot))
        val payload=JSONObject()
            .put("jk",premiumJk)
            .put("inverter",premiumInverter)
            .put("inverters",voltronicFleet.snapshot())
            .put("style",prefs.getString("energy_dashboard_style","modern"))
            .put("route",route)
            .put("updated",java.text.SimpleDateFormat(
                "'Actualisé à' HH:mm:ss",Locale.FRANCE).format(java.util.Date()))
        // Keep the obsolete premium WebView hidden at all times.
        premiumDashboard.visibility=View.GONE
        if(::connectionBadge.isInitialized)connectionBadge.text=route
        if(::energyDashboard.isInitialized)energyDashboard.showSnapshot(payload)
        if(currentRootPage==0) {
            val batteryHome=EnergyDashboardSelection.showBatteryHome(
                voltronicFleet.hasInverters(),
                hasAnyConfiguredJk() || premiumJk.optInt("total",0)>0)
            // Switching from a newly unconfigured installation to JK-only
            // happens once after the first successful BMS discovery.
            if(homeBatteryMode!=batteryHome)navigateNative?.invoke(0)
        }
    }

    private fun pollPremiumVoltronic(now:Long) {
        voltronicFleet.poll(now,voltronicWifiNetwork())
    }

    @Deprecated("Legacy Activity result for a local SAF JSON backup")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || requestCode !in listOf(711,712,713,714))return
        val uri=data?.data ?: return
        if(requestCode==713) {
            val json=SmartEnergySettingsMigration.sanitized(prefs)
            settingsWorker.execute {
                val msg=try {
                    val stream=contentResolver.openOutputStream(uri,"w")
                        ?:throw IllegalStateException("Destination indisponible")
                    stream.bufferedWriter(Charsets.UTF_8).use {it.write(json)}
                    "Configuration non secrète exportée. Ne partage pas ce fichier publiquement."
                }catch(ex:Exception) {
                    "Export échoué : "+ex.javaClass.simpleName
                }
                runOnUiThread {
                    if(::migrationFeedback.isInitialized)migrationFeedback.text=msg
                }
            }
            return
        }
        if(requestCode==714) {
            settingsWorker.execute {
                try {
                    val stream=contentResolver.openInputStream(uri)
                        ?:throw IllegalStateException("Fichier inaccessible")
                    val buffer=java.io.ByteArrayOutputStream()
                    stream.use { input ->
                        val chunk=ByteArray(2048)
                        while(true) {
                            val n=input.read(chunk)
                            if(n<0)break
                            buffer.write(chunk,0,n)
                            require(buffer.size()<=65536){"Fichier trop volumineux"}
                        }
                    }
                    val raw=buffer.toString("UTF-8")
                    val (count,summary)=SmartEnergySettingsMigration.preview(raw)
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Restaurer $count réglage(s) ?")
                            .setMessage(summary+"\n\n"+
                                "Aucun code ni token n'est importé. "+
                                "Les modifications restent locales : aucun "+
                                "équipement n'est commandé. Après validation, "+
                                "ferme et rouvre l'application puis renseigne "+
                                "les codes dans Configuration.")
                            .setNegativeButton("Annuler",null)
                            .setPositiveButton("Importer") { _,_ ->
                                val msg=try {
                                    val done=SmartEnergySettingsMigration.importApproved(prefs,raw)
                                    "$done réglage(s) enregistrés. "+
                                    "Redémarre l'application et renseigne les "+
                                    "codes ESP32 et le token VPN."
                                }catch(ex:Exception){
                                    "Import refusé : "+(ex.message?:"données invalides")
                                }
                                if(::migrationFeedback.isInitialized)
                                    migrationFeedback.text=msg
                            }.show()
                    }
                }catch(ex:Exception) {
                    runOnUiThread {
                        if(::migrationFeedback.isInitialized)
                            migrationFeedback.text="Import refusé : "+
                                (ex.message?:"fichier incompatible")
                    }
                }
            }
            return
        }
        if(requestCode==711) {
            val json=voltronicProfiles.exportedJson()
            settingsWorker.execute {
                val message=try {
                    val stream=contentResolver.openOutputStream(uri,"w")
                        ?: throw IllegalStateException("Destination indisponible")
                    stream.bufferedWriter(Charsets.UTF_8).use {it.write(json)}
                    "Profils Voltronic exportés. Aucun secret inclus."
                } catch(ex:Exception) {
                    "Export échoué : "+ex.javaClass.simpleName
                }
                runOnUiThread {
                    if(::voltronicProfileFeedback.isInitialized)
                        voltronicProfileFeedback.text=message
                }
            }
        } else {
            settingsWorker.execute {
                try {
                    val stream=contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Fichier inaccessible")
                    // Bounded import: no untrusted large input files or keys.
                    val bytes=stream.use { input ->
                        val buffer=java.io.ByteArrayOutputStream()
                        val chunk=ByteArray(2048)
                        while(true) {
                            val n=input.read(chunk)
                            if(n<0)break
                            buffer.write(chunk,0,n)
                            require(buffer.size()<=65536){"Fichier trop volumineux"}
                        }
                        buffer.toByteArray()
                    }
                    val raw=String(bytes,Charsets.UTF_8)
                    val (count,preview)=voltronicProfiles.previewImport(raw)
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("Importer $count profil(s) ?")
                            .setMessage(preview+"\n\n"+
                                "Sauvegarde locale uniquement. Aucun réglage "+
                                "ne sera envoyé aux onduleurs.")
                            .setNegativeButton("Annuler",null)
                            .setPositiveButton("Importer") { _,_ ->
                                val message=try {
                                    val imported=voltronicProfiles.importApproved(raw)
                                    "$imported profil(s) importé(s), sans modification matérielle."
                                }catch(ex:Exception) {
                                    "Import refusé : "+(ex.message?:"données invalides")
                                }
                                if(::voltronicProfileFeedback.isInitialized)
                                    voltronicProfileFeedback.text=message
                            }.show()
                    }
                } catch(ex:Exception) {
                    runOnUiThread {
                        if(::voltronicProfileFeedback.isInitialized)
                            voltronicProfileFeedback.text="Import refusé : "+
                                (ex.message?:"fichier invalide")
                    }
                }
            }
        }
    }

    private fun prepareBundledVoltronicOta(slot:Int) {
        if(slot !in 1..3 || !voltronicFleet.configured(slot))return
        val host=if(slot==1)prefs.getString("inverter_probe_ip","")?:""
            else prefs.getString("inverter_${slot}_ip","")?:""
        val port=if(slot==1)prefs.getInt("inverter_probe_port",18081)
            else prefs.getInt("inverter_${slot}_port",18081)
        val code=auxiliaryCode(if(slot==1)"premium_voltronic_code_cipher"
            else "premium_voltronic_${slot}_code_cipher")
        val network=voltronicWifiNetwork()
        if(network==null || !SunSpecReadOnly.isPrivateIPv4(host) ||
           code.length !in 8..32) {
            updateFeedback.text="INV$slot : réseau Wi-Fi local, IP privée et code S3 requis."
            return
        }
        updateFeedback.text="INV$slot : contrôle de la version 0.3.2, des partitions et du BIN intégré…"
        settingsWorker.execute {
            val preflight=try {
                VoltronicBundledOta.preflight(this,host,code,port,network)
            }catch(ex:Exception) {
                runOnUiThread {
                    if(!isFinishing)updateFeedback.text="OTA test INV$slot : "+
                        (ex.message?:ex.javaClass.simpleName).take(190)+
                        ". Aucune écriture effectuée."
                }
                return@execute
            }
            runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("CONFIRMER l'OTA · INV$slot")
                    .setMessage("ESP32-S3 : ${preflight.installedVersion}\n"+
                        "Nouvelle version : ${VoltronicBundledOta.TO_VERSION}\n"+
                        "Partition disponible : ${preflight.maxImageBytes} octets\n\n"+
                        "Le fichier intégré a été contrôlé par SHA-256. "+
                        "Le S3 redémarrera et les mesures seront interrompues brièvement. "+
                        "L'opération modifie le firmware de cette passerelle Voltronic "+
                        "(pas les réglages de l'onduleur, ni JK/VPN). "+
                        "Garder l'alimentation et le Wi-Fi stables. "+
                        "Aucun nouvel essai automatique en cas d'incertitude.\n\n"+
                        "Lancer la mise à jour sur INV$slot ?")
                    .setNegativeButton("Annuler",null)
                    .setPositiveButton("Installer 0.3.4") { _,_->
                        updateFeedback.text="OTA test INV$slot : pré-vérification…"
                        settingsWorker.execute {
                            val message=try {
                                VoltronicBundledOta.install(this,host,code,port,network) {
                                    progress -> runOnUiThread {
                                        if(!isFinishing)updateFeedback.text=
                                            "INV$slot : $progress"
                                    }
                                }
                                if(VoltronicBundledOta.verifyAfterReboot(host,code,port,network)) {
                                    "INV$slot : OTA 0.3.4 confirmée après redémarrage. "+
                                    "Vérifie maintenant la télémétrie et les paramètres en lecture."
                                }else {
                                    "INV$slot : envoi accepté, mais nouvelle version non vérifiée. "+
                                    "Contrôler la version et la connexion avant tout autre essai."
                                }
                            }catch(ex:Exception) {
                                "INV$slot : état OTA non confirmé ("+
                                    (ex.message?:ex.javaClass.simpleName).take(140)+
                                    "). Ne pas relancer sans vérifier la version actuelle."
                            }
                            runOnUiThread {
                                if(!isFinishing) {
                                    updateFeedback.text=message
                                    AlertDialog.Builder(this)
                                        .setTitle("Résultat OTA · INV$slot")
                                        .setMessage(message)
                                        .setPositiveButton("OK",null).show()
                                }
                            }
                        }
                    }.show()
                updateFeedback.text="INV$slot : contrôle préalable réussi. Confirmation demandée."
            }
        }
    }

    /** No flash/OTA here: JK currently advertises ota=false.
     * Read each saved reader independently without touching BLE or JK settings. */
    private fun checkJkFirmwareUpdates() {
        val wifi=voltronicWifiNetwork()
        if(wifi==null || selectedRoute()==JkTransportPolicy.Route.GATEWAY_VPN) {
            updateFeedback.text="ESP JK : vérification firmware réservée au Wi-Fi local. "+
                "Le VPN ne permet pas de flasher un lecteur."
            return
        }
        data class Reader(val slot:Int,val host:String,val code:String)
        val readers=(1..3).mapNotNull { slot ->
            val host=if(slot==activeReaderSlot)prefs.getString("local_ip","")?:""
                else prefs.getString(slotKey(slot,"local_ip"),"")?:""
            val code=slotPairingCode(slot)
            if(allowedLocalIp(host) && code.length in 8..32)
                Reader(slot,host,code) else null
        }
        if(readers.isEmpty()) {
            updateFeedback.text="Aucun ESP JK associé avec une IP et un code local."
            return
        }
        updateFeedback.text="Vérification des versions de ${readers.size} lecteur(s) JK…"
        settingsWorker.execute {
            // No automatic flash: two checks before offering the OTA,
            // the official SHA-pinned catalog and the reader's live partition.
            val catalog=try {
                SmartEnergyUpdateFeed.fetchPublicManifest(SmartEnergyUpdateFeed.JK_MANIFEST)
            }catch(_:Exception){null}
            val available=mutableListOf<Pair<Reader,SmartEnergyUpdateFeed.FirmwareRelease>>()
            val summary=readers.map { reader ->
                try {
                    val url=URL("http://${reader.host}/api/v1/info")
                    val c=wifi.openConnection(url) as HttpURLConnection
                    try {
                        c.requestMethod="GET"
                        c.instanceFollowRedirects=false
                        c.connectTimeout=3000
                        c.readTimeout=5000
                        c.setRequestProperty("X-Setup-Code",reader.code)
                        require(c.responseCode==200){"HTTP ${c.responseCode}"}
                        val data=JSONObject(c.inputStream.bufferedReader().use {
                            it.readText().take(4096)
                        })
                        require(data.optString("product")=="smart-jk-esp32") {
                            "Appareil JK non reconnu"
                        }
                        val version=data.optString("version","inconnue")
                        val advertised=data.optBoolean("ota",false)
                        val newer=catalog?.let {
                            SmartEnergyUpdateFeed.parseJk(it,version)
                        }
                        if(!advertised)
                            "ESP${reader.slot} · $version : pas encore d'OTA sur ce firmware. "+
                            "Première migration USB nécessaire, sans effacer NVS."
                        else {
                            val ready=try {
                                JkLocalOta.status(reader.host,reader.code,wifi)
                            }catch(_:Exception){null}
                            if(ready?.optBoolean("flash_ready")==true &&
                               ready.optString("partition_scheme")=="dual_ota" &&
                               newer?.compatible==true) {
                                available.add(reader to newer)
                                "ESP${reader.slot} · $version → ${newer.version} : mise à jour disponible."
                            }else if(ready==null)
                                "ESP${reader.slot} · $version : vérifier le service OTA (aucun flash)."
                            else if(!ready.optBoolean("ota_supported") ||
                                    !ready.optBoolean("flash_ready"))
                                "ESP${reader.slot} · $version : OTA momentanément bloquée "+
                                "(partition ou firmware non prêt)."
                            else "ESP${reader.slot} · $version : aucune nouvelle MAJ JK publiée."
                        }
                    }finally{c.disconnect()}
                }catch(ex:Exception) {
                    "ESP${reader.slot} : vérification impossible ("+
                        (ex.message?:ex.javaClass.simpleName).take(60)+")"
                }
            }.joinToString("\n")
            runOnUiThread {
                if(isFinishing || !::updateFeedback.isInitialized)return@runOnUiThread
                updateFeedback.text=summary
                if(available.isNotEmpty()) {
                    val choices=available.map {
                        "ESP${it.first.slot} → ${it.second.version}"
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Mise à jour OTA JK disponible")
                        .setMessage("Choisis un lecteur. Vérification SHA-256 et confirmation "+
                            "avant le flash. Conserve son alimentation, et ses paramètres "+
                            "Wi-Fi, BLE et association en NVS.")
                        .setItems(choices) { _,which ->
                            val (reader,release)=available[which]
                            AlertDialog.Builder(this)
                                .setTitle("CONFIRMER OTA JK · ESP${reader.slot}")
                                .setMessage("Installer ${release.version} sur ESP${reader.slot} "+
                                    "depuis GitHub, exclusivement en Wi-Fi local ? "+
                                    "Ne débranche pas l'ESP32 pendant l'opération.")
                                .setNegativeButton("Annuler",null)
                                .setPositiveButton("Installer") { _,_ ->
                                    updateFeedback.text="ESP${reader.slot} : OTA JK en cours…"
                                    settingsWorker.execute {
                                        val message=try {
                                            JkLocalOta.install(this,release,reader.host,
                                                reader.code,wifi) {
                                                progress->runOnUiThread {
                                                    updateFeedback.text=progress
                                                }
                                            }
                                            if(JkLocalOta.confirmVersion(reader.host,
                                                    reader.code,wifi,release.version))
                                                "ESP${reader.slot} : firmware ${release.version} "+
                                                "confirmé après redémarrage."
                                            else "ESP${reader.slot} : OTA reçue, mais version "+
                                                "non confirmée. Contrôle l'ESP avant tout nouvel essai."
                                        }catch(ex:Exception) {
                                            "ESP${reader.slot} : mise à jour non confirmée ("+
                                                (ex.message?:ex.javaClass.simpleName).take(120)+
                                                "). Aucun nouvel essai automatique."
                                        }
                                        runOnUiThread {if(!isFinishing)updateFeedback.text=message}
                                    }
                                }.show()
                        }.setNegativeButton("Plus tard",null).show()
                }
            }
        }
    }

    private fun checkVoltronicOtaReadiness(slot:Int) {
        if(slot !in 1..3 || !voltronicFleet.configured(slot))return
        val host=if(slot==1)prefs.getString("inverter_probe_ip","")?:""
            else prefs.getString("inverter_${slot}_ip","")?:""
        val port=if(slot==1)prefs.getInt("inverter_probe_port",18081)
            else prefs.getInt("inverter_${slot}_port",18081)
        val code=auxiliaryCode(if(slot==1)"premium_voltronic_code_cipher"
            else "premium_voltronic_${slot}_code_cipher")
        val network=voltronicWifiNetwork()
        if(network==null || !SunSpecReadOnly.isPrivateIPv4(host)
            || code.length !in 8..32) {
            updateFeedback.text="INV$slot : Wi-Fi local, IP privée et code S3 requis."
            return
        }
        updateFeedback.text="INV$slot : lecture des partitions OTA…"
        settingsWorker.execute {
            val message=try {
                val status=VoltronicLocalOta.status(host,code,port,network)
                val scheme=status.optString("partition_scheme","inconnu")
                val version=status.optString("running_firmware",
                    voltronicFleet.snapshotFor(slot).optString("firmwareVersion",""))
                val pending=status.optBoolean("rollback_pending",false)
                val ready=status.optBoolean("ota_supported",false)
                "INV$slot · firmware $version · partitions $scheme · "+
                    (if(pending)"rollback en attente : aucune OTA autorisée"
                     else if(ready)"OTA possible après publication officielle et SHA-256"
                     else "migration USB nécessaire : OTA refusée sans écriture")
            }catch(ex:Exception) {
                "INV$slot : contrôle OTA inaccessible ("+
                    ex.javaClass.simpleName.take(50)+"). Aucune écriture effectuée."
            }
            runOnUiThread {if(::updateFeedback.isInitialized)updateFeedback.text=message}
        }
    }

    private fun checkVoltronicOtaForSlot(slot:Int) {
        if(slot !in 1..3 || !voltronicFleet.configured(slot))return
        val host=if(slot==1)prefs.getString("inverter_probe_ip","")?:""
            else prefs.getString("inverter_${slot}_ip","")?:""
        val port=if(slot==1)prefs.getInt("inverter_probe_port",18081)
            else prefs.getInt("inverter_${slot}_port",18081)
        val code=auxiliaryCode(if(slot==1)"premium_voltronic_code_cipher"
            else "premium_voltronic_${slot}_code_cipher")
        val network=voltronicWifiNetwork()
        if(network==null || selectedRoute()==JkTransportPolicy.Route.GATEWAY_VPN) {
            updateFeedback.text="Mise à jour Voltronic : connecte-toi en Wi-Fi local, "+
                "hors de la connexion VPN JK."
            return
        }
        if(!SunSpecReadOnly.isPrivateIPv4(host) || code.length !in 8..32) {
            updateFeedback.text="Configure l'IP et le code local du lecteur INV$slot."
            return
        }
        updateFeedback.text="INV$slot : lecture du firmware et recherche des MAJ…"
        settingsWorker.execute {
            try {
                // Always read live version on the actual target, never a stale QPIGS snapshot.
                val info=VoltronicReadOnlyClient.probe(host,code,port,network)
                require(info.optString("product")=="smart-energy-voltronic-s3" &&
                    info.optString("chip")=="esp32s3") {
                    "ESP32-S3 Voltronic non reconnu"
                }
                val version=info.optString("version","")
                require(version.isNotBlank()){"Version firmware illisible"}
                val status=VoltronicLocalOta.status(host,code,port,network)
                if(!status.optBoolean("ota_supported",false) ||
                    status.optString("partition_scheme")!="dual_ota") {
                    runOnUiThread {
                        updateFeedback.text="INV$slot · firmware $version : "+
                            "OTA indisponible (partitionnement à migrer en USB)."
                    }
                    return@execute
                }
                val manifest=SmartEnergyUpdateFeed.fetchPublicManifest(
                    SmartEnergyUpdateFeed.VOLTRONIC_MANIFEST)
                val feed=SmartEnergyUpdateFeed.parseVoltronic(manifest,version)
                if(feed==null && version=="0.3.2-dev-ota-gated-read-only") {
                    // Backward-compatible single test image, pre-validated and embedded.
                    // Do not route through the unpublished GitHub firmware catalog.
                    runOnUiThread {
                        if(!isFinishing && !isDestroyed)
                            prepareBundledVoltronicOta(slot)
                    }
                    return@execute
                }
                if(feed==null) {
                    runOnUiThread {
                        updateFeedback.text="INV$slot : firmware $version. "+
                            "Aucune mise à jour Voltronic plus récente publiée."
                    }
                    return@execute
                }
                require(feed.compatible){"Version proposée incompatible avec l'ESP32-S3"}
                require(!status.optBoolean("rollback_pending",false)) {
                    "Rollback en attente : mise à jour refusée"
                }
                if(status.has("flash_ready"))require(status.optBoolean("flash_ready")) {
                    "ESP32 non prêt à recevoir un firmware"
                }
                runOnUiThread {
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("OTA Voltronic INV$slot : ${feed.version}")
                        .setMessage("Actuel : $version\n"+
                            "Nouveau : ${feed.version}\n\n"+
                            "Téléchargement GitHub contrôlé par SHA-256, "+
                            "puis transfert Wi-Fi local avec confirmation "+
                            "et redémarrage de l'ESP32-S3. "+
                            "Aucun réglage JK, VPN ou onduleur n'est modifié.")
                        .setNegativeButton("Annuler",null)
                        .setPositiveButton("Installer la mise à jour") { _,_ ->
                            updateFeedback.text="INV$slot : transfert OTA…"
                            settingsWorker.execute {
                                val message=try {
                                    VoltronicLocalOta.install(this,feed,host,code,port,
                                        network){statusText->
                                            runOnUiThread {updateFeedback.text=statusText}
                                        }
                                    var verified=false
                                    repeat(12) {
                                        if(!verified){
                                            Thread.sleep(2500)
                                            try {
                                                verified=VoltronicReadOnlyClient.probe(
                                                    host,code,port,network)
                                                    .optString("version")==feed.version
                                            }catch(_:Exception){}
                                        }
                                    }
                                    if(verified)
                                        "INV$slot : OTA ${feed.version} confirmée après redémarrage."
                                    else "INV$slot : OTA acceptée, mais version non confirmée. "+
                                        "Ne relance pas l'installation automatiquement."
                                }catch(ex:Exception) {
                                    "INV$slot : état OTA incertain ("+
                                        (ex.message?:ex.javaClass.simpleName).take(120)+
                                        "). Vérifie la version avant tout nouvel essai."
                                }
                                runOnUiThread {if(!isFinishing)updateFeedback.text=message}
                            }
                        }.show()
                }
            }catch(ex:Exception) {
                runOnUiThread {
                    if(!isFinishing)updateFeedback.text="INV$slot : vérification impossible ("+
                        (ex.message?:ex.javaClass.simpleName).take(135)+")."
                }
            }
        }
    }

    private fun loadEditableVoltronicSettings(slot:Int) {
        if(slot !in 1..3 || !voltronicFleet.configured(slot))return
        val host=if(slot==1)prefs.getString("inverter_probe_ip","")?:""
            else prefs.getString("inverter_${slot}_ip","")?:""
        val port=if(slot==1)prefs.getInt("inverter_probe_port",18081)
            else prefs.getInt("inverter_${slot}_port",18081)
        val code=auxiliaryCode(if(slot==1)"premium_voltronic_code_cipher"
            else "premium_voltronic_${slot}_code_cipher")
        val useVpn=selectedRoute()==JkTransportPolicy.Route.GATEWAY_VPN
        val route=if(useVpn)voltronicVpnRoute() else null
        val network=if(useVpn)null else voltronicWifiNetwork()
        if((useVpn && route==null) ||
           (!useVpn && (network==null || !SunSpecReadOnly.isPrivateIPv4(host) ||
                        code.length !in 8..32))) {
            AlertDialog.Builder(this).setTitle("INV$slot · connexion requise")
                .setMessage(if(useVpn)
                    "Vérifie que Tailscale et le VPN ESP32 JK sont connectés. "+
                    "Les écritures distantes nécessitent le nouveau firmware "+
                    "de la passerelle et une autorisation USB explicite."
                 else "Connecte le téléphone au Wi-Fi local et renseigne "+
                    "l'adresse privée et le code ESP32-S3 dans Configuration.")
                .setPositiveButton("Compris",null).show()
            return
        }
        // A blocking informational dialog used to remain on top of the actual
        // editor after every read, even when a write was already completed.
        // Use a non-blocking toast; the actual settings/error dialog appears
        // only after the background read finishes.
        android.widget.Toast.makeText(this,
            "INV$slot : relecture QPIRI/QDOP…",
            android.widget.Toast.LENGTH_SHORT).show()
        settingsWorker.execute {
            val settings=try {
                VoltronicParameterEditor.current(host,code,port,network,route,slot)
            }catch(ex:Exception) {
                runOnUiThread {
                    if(!isFinishing)AlertDialog.Builder(this)
                        .setTitle("Paramètres INV$slot indisponibles")
                        .setMessage(ex.message?:ex.javaClass.simpleName)
                        .setPositiveButton("OK",null).show()
                }
                return@execute
            }
            runOnUiThread {
                if(isFinishing||isDestroyed)return@runOnUiThread
                if(settings.isEmpty()){
                    AlertDialog.Builder(this)
                        .setTitle("INV$slot · aucune valeur fraîche")
                        .setMessage("Attends une lecture QPIRI/QDOP valide sur le "+
                            "lecteur S3. Aucun paramètre n'a été modifié.")
                        .setPositiveButton("OK",null).show()
                }else AlertDialog.Builder(this)
                    .setTitle("Paramètres Voltronic · INV$slot")
                    .setItems(settings.map { "${it.label} : ${it.value} ${it.unit}" }
                        .toTypedArray()) { _,index ->
                        proposeVoltronicChange(slot,host,port,code,network,route,
                            settings[index])
                    }.show()
            }
        }
    }

    private fun proposeVoltronicChange(
        slot:Int,host:String,port:Int,code:String,network:Network?,
        route:VoltronicVpnRoute?,setting:VoltronicParameterEditor.Setting
    ) {
        val value=input("Nouvelle valeur ${setting.unit}")
        value.inputType=android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        value.setText(setting.value.toString())
        AlertDialog.Builder(this)
            .setTitle("INV$slot · ${setting.label}")
            .setMessage("Valeur actuelle native : ${setting.value} ${setting.unit}. "+
                "Entre la nouvelle valeur. Le firmware refusera un pas "+
                "ou une plage non compatibles.")
            .setView(value)
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Continuer") { _,_ ->
                val desired=value.text.toString().trim().replace(',','.').toDoubleOrNull()
                if(desired==null || !desired.isFinite() || desired==setting.value) {
                    AlertDialog.Builder(this).setTitle("Valeur incorrecte")
                        .setMessage("Indique une nouvelle valeur numérique différente.")
                        .setPositiveButton("OK",null).show()
                }else {
                    AlertDialog.Builder(this)
                        .setTitle("Confirmer l'écriture INV$slot")
                        .setMessage("${setting.label}\n"+
                            "Valeur actuelle : ${setting.value} ${setting.unit}\n"+
                            "Nouvelle valeur : $desired ${setting.unit}\n\n"+
                            "La valeur sera relue juste avant l'écriture. "+
                            (if(route!=null)
                                "MODIFICATION À DISTANCE via VPN Tailscale. "+
                                "Le changement sera appliqué sur l'onduleur réel. "
                              else "")+
                            "Une seule commande sera envoyée, puis une "+
                            "relecture native immédiate vérifiera le résultat. "+
                            "Si la confirmation échoue, aucun nouvel essai "+
                            "ne sera déclenché automatiquement.")
                        .setNegativeButton("Annuler",null)
                        .setPositiveButton("Confirmer et vérifier") { _,_ ->
                            updateFeedback.text="INV$slot : transaction en cours. "+
                                "Ne lance pas une seconde modification."
                            settingsWorker.execute {
                                val status=try {
                                    val result=VoltronicParameterEditor.change(
                                        host,code,port,network,setting.key,
                                        setting.value,desired,route,slot)
                                    if(result.optBoolean("verified",false))
                                        "INV$slot : paramètre vérifié par relecture native."
                                    else "INV$slot : modification NON CONFIRMÉE ("+
                                        result.optString("result","inconnu")+"). "+
                                        "Relis les paramètres avant toute autre action."
                                }catch(ex:Exception){
                                    "INV$slot : état non confirmé ("+
                                        (ex.message?:ex.javaClass.simpleName).take(130)+
                                        "). Vérifie l'onduleur, sans réessai automatique."
                                }
                                runOnUiThread {
                                    if(!isFinishing){
                                        updateFeedback.text=status
                                        AlertDialog.Builder(this)
                                            .setTitle("Résultat Voltronic · INV$slot")
                                            .setMessage(status)
                                            .setPositiveButton("OK",null).show()
                                    }
                                }
                            }
                        }.show()
                }
            }.show()
    }

    private fun installAndroidRelease(release:SmartEnergyUpdateFeed.AndroidRelease) {
        if(!::updateFeedback.isInitialized)return
        updateFeedback.text="Préparation de la mise à jour Android…"
        settingsWorker.execute {
            try {
                SmartEnergyApkInstaller.downloadAndVerify(this,release) { progress ->
                    runOnUiThread {
                        if(::updateFeedback.isInitialized)updateFeedback.text=progress
                    }
                }
                runOnUiThread {
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle("APK vérifiée : ${release.version}")
                        .setMessage("Empreinte SHA-256, identité de paquet et "+
                            "certificat Android contrôlés. Android installera "+
                            "la nouvelle version par-dessus celle-ci. "+
                            "Ne désinstalle pas l'application : "+
                            "tes associations et codes restent enregistrés.")
                        .setNegativeButton("Annuler",null)
                        .setPositiveButton("Ouvrir l'installateur Android") { _,_ ->
                            try {
                                if(!SmartEnergyApkInstaller.openAndroidInstaller(this))
                                    updateFeedback.text="Autorise Smart Energy à installer "+
                                        "ses mises à jour, puis utilise le bouton "+
                                        "« Terminer l'installation » de cet onglet."
                            }catch(ex:Exception){
                                updateFeedback.text="Installation Android : "+
                                    (ex.message?:ex.javaClass.simpleName)
                            }
                        }.show()
                }
            }catch(ex:Exception) {
                runOnUiThread {
                    if(::updateFeedback.isInitialized)updateFeedback.text=
                        "APK non installée : "+(ex.message?:ex.javaClass.simpleName).take(160)
                }
            }
        }
    }

    private fun checkSmartEnergyUpdates(manual:Boolean) {
        if(!::updateFeedback.isInitialized)return
        val now=System.currentTimeMillis()
        if(!manual && now-prefs.getLong("updates_last_check_ms",0L)<86400000L)return
        prefs.edit().putLong("updates_last_check_ms",now).apply()
        if(manual)updateFeedback.text="Vérification des publications GitHub…"
        settingsWorker.execute {
            val code=if(Build.VERSION.SDK_INT>=28)
                packageManager.getPackageInfo(packageName,0).longVersionCode
                else @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName,0).versionCode.toLong()
            var apk:SmartEnergyUpdateFeed.AndroidRelease?=null
            var fw:SmartEnergyUpdateFeed.FirmwareRelease?=null
            var appFailure:String?=null
            var fwFailure:String?=null
            try {
                apk=SmartEnergyUpdateFeed.parseAndroid(
                    SmartEnergyUpdateFeed.fetchPublicManifest(
                        SmartEnergyUpdateFeed.ANDROID_MANIFEST),code)
            } catch(ex:Exception){appFailure=ex.javaClass.simpleName}
            try {
                val firmwareVersion=voltronicFleet.snapshotFor(1)
                    .optString("firmwareVersion","")
                fw=SmartEnergyUpdateFeed.parseVoltronic(
                    SmartEnergyUpdateFeed.fetchPublicManifest(
                        SmartEnergyUpdateFeed.VOLTRONIC_MANIFEST),firmwareVersion)
            }catch(ex:Exception){fwFailure=ex.javaClass.simpleName}
            runOnUiThread {
                if(isFinishing || !::updateFeedback.isInitialized)return@runOnUiThread
                val appSummary=when {
                    apk!=null -> "APK Android ${apk!!.version} disponible."
                    appFailure!=null -> "Android : GitHub indisponible ($appFailure)."
                    else -> "Android : aucune nouvelle version publiée."
                }
                val fwSummary=when {
                    fw!=null -> "S3 ${fw!!.version} publiée ; OTA non activée sur les lecteurs actuels."
                    fwFailure!=null -> "Firmware : vérification impossible ($fwFailure)."
                    else -> "S3 : aucune nouvelle version publiée."
                }
                updateFeedback.text=appSummary+"\n"+fwSummary
                val release=apk
                if(release!=null &&
                    prefs.getLong("updates_last_alert_code",0L)<release.versionCode) {
                    AlertDialog.Builder(this)
                        .setTitle("Smart Energy ${release.version} disponible")
                        .setMessage("Une nouvelle APK est publiée sur GitHub. "+
                            "Télécharge-la puis installe-la sur la version actuelle "+
                            "sans désinstaller l'application. "+
                            "Une signature identique est indispensable.")
                        .setNegativeButton("Plus tard") { _,_ ->
                            prefs.edit().putLong("updates_last_alert_code",
                                release.versionCode).apply()
                        }
                        .setPositiveButton("Télécharger et installer") { _,_ ->
                            prefs.edit().putLong("updates_last_alert_code",
                                release.versionCode).apply()
                            installAndroidRelease(release)
                        }.show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkSmartEnergyUpdates(false)
        // Already-seen values appear immediately, never re-stamped as fresh.
        lastTelemetry?.let { showTelemetry(it) }
        uiHandler.removeCallbacks(telemetryTicker)
        // Read all paired ESP32 HTTP snapshots immediately, without waiting
        // for /info + /bms from the foreground connection.
        warmReaderSnapshots()
        uiHandler.postDelayed(telemetryTicker, 200)
    }
    override fun onPause() {
        uiHandler.removeCallbacks(telemetryTicker)
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(telemetryTicker)
        discoverListener?.let { listener ->
            try { (getSystemService(Context.NSD_SERVICE) as NsdManager).stopServiceDiscovery(listener) }
            catch (_: Exception) {}
        }
        discoverListener = null
        releaseConnection()
        io.shutdownNow()
        fleetWorker.shutdownNow()
        readerWarmWorker.shutdownNow()
        settingsWorker.shutdownNow()
        inverterWorker.shutdownNow()
        inverterEnergyWorker.shutdownNow()
        super.onDestroy()
    }
}
