package fr.smartenergy.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var readerSelector: LinearLayout
    private lateinit var readerHeader: TextView
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
    private lateinit var parameterFeedback: TextView
    private var lastParameterFeedback: String = ""
    @Volatile private var telemetryBusy = false
    private val telemetryTicker = object : Runnable {
        override fun run() {
            if (!lanVerified && !lanConnectionPending && !setupTransport) {
                val now=android.os.SystemClock.elapsedRealtime()
                val route=selectedRoute()
                when(route) {
                    JkTransportPolicy.Route.GATEWAY_VPN -> {
                        if(now-lastRemoteAttempt>=30000L) {
                            lastRemoteAttempt=now
                            connectGatewayRemote()
                        }
                    }
                    JkTransportPolicy.Route.LOCAL_WIFI -> {
                        val savedIp=prefs.getString("local_ip","") ?: ""
                        if(!remoteGatewayConnection && now-lastLocalAttempt>=20000L) {
                            lastLocalAttempt=now
                            localHost=savedIp
                            lanIp.setText(savedIp)
                            connectHomeLan(false)
                        }
                    }
                    JkTransportPolicy.Route.CONFIGURE_VPN,
                    JkTransportPolicy.Route.NO_CONNECTION -> {
                        // Never try the stale 192.168.x.x IP when a VPN is on
                        // or the user has selected ESP32 VPN explicitly.
                    }
                }
            }
            // Tailscale just became active while we were using LAN: pause
            // local polling until the user configures or selects the VPN.
            if(lanVerified && !remoteGatewayConnection && !remoteVpnConnection &&
               !setupTransport &&
               selectedRoute()==JkTransportPolicy.Route.CONFIGURE_VPN &&
               vpnConnected()) {
                lanVerified=false
                telemetryStatus.text="VPN actif : configure « Accès extérieur — ESP32 VPN »." +
                    " Le réseau local ne sera plus sollicité automatiquement."
            }
            if (lanVerified && !remoteGatewayConnection && !remoteVpnConnection &&
                !setupTransport && selectedRoute()==JkTransportPolicy.Route.GATEWAY_VPN &&
                !lanConnectionPending) {
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-lastRemoteAttempt>=30000L) {
                    lastRemoteAttempt=now
                    connectGatewayRemote()
                }
            }
            if (lanVerified || (setupTransport && apNetwork != null)) {
                refreshTelemetry()
                if (currentTab == 2 && android.os.SystemClock.elapsedRealtime() - lastDetailsFetchedAt > 30000L) {
                    refreshDetails()
                }
            }
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

    private fun section(root: LinearLayout, heading: String): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel)
            setPadding(d(15), d(16), d(15), d(16))
        }
        root.addView(p, LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(12) })
        p.addView(label(heading, 17f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(10) })
        return p
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
        activeReaderSlot=prefs.getInt("active_reader_slot",1).coerceIn(1,3)
        val scroll = ScrollView(this).apply { setBackgroundColor(this@MainActivity.background) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(24), d(16), d(30))
        }
        scroll.addView(root)
        setContentView(scroll)

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandLogo = ImageView(this).apply {
            setImageResource(R.drawable.smart_energy_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Logo Smart Energy"
        }
        brand.addView(brandLogo, LinearLayout.LayoutParams(d(54), d(54)))
        brand.addView(label("SMART ENERGY", 17f, true, mint),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8) })
        add(root, brand, 2)
        val dashboardPage=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val configurationPage=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fun showConfiguration(show:Boolean) {
            dashboardPage.visibility=if(show) View.GONE else View.VISIBLE
            configurationPage.visibility=if(show) View.VISIBLE else View.GONE
            parameterEditingEnabled=false
            mountedTab=-1
            if(!show) lastTelemetry?.let { showTelemetry(it) }
            scroll.post { scroll.scrollTo(0,0) }
        }
        val navigation=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        for((title,config) in listOf("Tableau de bord" to false,"Configuration et association" to true)) {
            val item=action(title) { showConfiguration(config) }
            item.textSize=12f
            navigation.addView(item,LinearLayout.LayoutParams(0,d(48),1f).apply {
                rightMargin=d(4);leftMargin=d(4)
            })
        }
        add(root,navigation,14)
        root.addView(dashboardPage)
        root.addView(configurationPage)
        val startInConfig=storedCode().isBlank()
        dashboardPage.visibility=if(startInConfig) View.GONE else View.VISIBLE
        configurationPage.visibility=if(startInConfig) View.VISIBLE else View.GONE
        add(dashboardPage, label("Tableau de bord JK", 25f, true), 18)
        status = label("Connexion à la passerelle…", 13f, false, subdued)
        add(dashboardPage, status, 8)
        telemetryStatus = label("En attente des mesures réelles du JK", 13f, false, subdued)
        add(dashboardPage, telemetryStatus, 10)
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
        parameterFeedback=label("",12f,false,mint).apply { visibility=View.GONE }
        add(dashboardPage, parameterFeedback, 6)
        highlightActiveTab()
        telemetryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(dashboardPage, telemetryPanel, 8)
        add(dashboardPage, action("⟳ Actualiser les mesures") { refreshTelemetry() }, 8)
        add(dashboardPage, action("Se connecter via ESP32 VPN / Tailscale") {
            prefs.edit().putString("connection_preference","gateway").apply()
            connectGatewayRemote()
        }, 8)
        add(dashboardPage, label("Mesures expérimentales. Les réglages du BMS ne peuvent être modifiés qu'après un déverrouillage explicite.", 12f, color = subdued), 8)
        val advanced=configurationPage
        add(advanced,label("Configuration et associations",25f,true),18)
        val readers=section(advanced,"Mes passerelles ESP32 JK (2 BMS maximum chacune)")
        add(readers,label("Trois lecteurs JK indépendants, soit jusqu'à six BMS. Sélectionne un lecteur avant de l'associer, le configurer ou choisir ses batteries.",12f,color=subdued))
        readerSelector=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        add(readers,readerSelector,6)
        readerHeader=label("",13f,false,subdued)
        add(readers,readerHeader,8)
        refreshReaderSelector()

        val lan = section(advanced, "Connexion quotidienne — Wi-Fi maison")
        add(lan, label("L’application mémorise le code après association. Si une réinstallation l’a effacé, renseigne le code dans la section 2 puis clique ici : pas besoin de reconfigurer la box.", color = subdued))
        lanIp = input("Adresse IP locale de l'ESP32 (ex. 192.168.1.50)")
        lanIp.setText(prefs.getString("local_ip", "") ?: "")
        add(lan, lanIp)
        lanConnectBtn = action("Se connecter à ma passerelle") {
            prefs.edit().putString("connection_preference","local").apply()
            connectHomeLan()
        }
        add(lan, lanConnectBtn)
        physicalRecoverBtn = action("Associer avec BOOT (première fois / code oublié)") { beginBootPairing() }
        add(lan, physicalRecoverBtn)
        bootCommitBtn = action("Après le 2e BOOT : confirmer l'association") { commitBootPairing() }
        add(lan, bootCommitBtn)
        add(lan, label("ESP32 JK v0.3.1 : appuie 4 secondes sur BOOT, choisis un code de 8 à 32 caractères dans l'application, puis confirme par un second appui BOOT de 2 secondes. Aucun ancien code ni USB nécessaires ; Wi-Fi et BMS conservés.",12f,color=subdued))
        lanDiscoverBtn = action("Rechercher sur mon Wi-Fi") { discoverHomeLan() }
        add(lan, lanDiscoverBtn)
        autoEspBtn = action("Trouver un ESP32 proche") { findNearbyEsp32() }
        add(lan, autoEspBtn)
        add(lan, label("La recherche utilise mDNS ; si elle échoue, renseigne une fois l'adresse IP vue dans les journaux ESP32.", 12f, color = subdued))

        val remote = section(advanced, "Accès extérieur — ESP32 VPN")
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
            prefs.edit().putString("connection_preference","gateway").apply()
            connectGatewayRemote()
        }, 10)
        remoteCheck = CheckBox(this).apply {
            text = "Connexion VPN automatique lorsque Tailscale est actif"
            setTextColor(normal)
            isChecked = prefs.getBoolean("remote_gateway_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("remote_gateway_enabled", checked).apply()
                remoteStatus.text = if (checked)
                    "Connexion automatique activée pour le prochain démarrage."
                    else "Connexion VPN automatique désactivée."
            }
        }
        add(remote, remoteCheck)
        add(remote, label("Pour la première association du JK et les changements de réglage, utilise toujours le Wi-Fi local. Le VPN reste en lecture seule.", 12f, color = subdued))
        add(remote, action("Revenir à ma connexion Wi-Fi locale") {
            prefs.edit().putString("connection_preference","local").apply()
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

        val a = section(advanced, "2. Première association / dépannage")

        add(a, label("Méthode recommandée : « Associer avec BOOT » dans la section 1. Le code personnel est choisi une fois et conservé chiffré. Cette section permet aussi d'utiliser une ancienne passerelle déjà configurée.", color = subdued))
        apName = input("SmartJK-Setup-XXXXXX")
        setupCode = input("Code unique de l'ESP32 (vide = code mémorisé)", true)
        apName.setText(prefs.getString("setup_ssid", "") ?: "")
        add(a, apName)
        add(a, setupCode)
        add(a, action("Connecter l'ESP32") { connectSetupAp() }, 12)
        add(a, label("Avec le mode BOOT 0.3.1, l'application rejoint automatiquement le Wi-Fi temporaire et demande une seconde confirmation physique ; il reste ouvert au maximum 2 minutes.", 12f, color = subdued))

        val b = section(advanced, "3. Gérer les JK BMS enregistrés")
        add(b, label("Chaque lecteur accepte deux BMS dans l'application. Décoche un BMS puis sauvegarde pour le retirer ; son adresse reste connue pour le réassocier. Utilise Renommer pour personnaliser chaque batterie.",13f,color=subdued))
        add(b, action("Afficher mes BMS enregistrés") { loadSavedBms() }, 8)
        scanBtn = action("Rechercher de nouveaux appareils Bluetooth") { scanBle() }
        add(b, scanBtn)
        scanPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(b, scanPanel)
        saveBtn = action("Sauvegarder les batteries choisies") { saveSelection() }
        add(b, saveBtn)
        add(b, label("Une case décochée ne prend effet qu'après « Sauvegarder ». La suppression retire seulement l'adresse de la liste ESP32 et termine cette connexion BLE ; les paramètres physiques du BMS ne sont jamais effacés. Les anciennes MAC restent connues de cette application pour être réactivées.",12f,color=subdued))

        val wifiMaintenance=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            visibility=View.GONE
        }
        add(advanced, action("Changer le Wi-Fi de l'ESP32 (dépannage)") {
            wifiMaintenance.visibility=
                if(wifiMaintenance.visibility==View.VISIBLE) View.GONE else View.VISIBLE
        }, 12)
        advanced.addView(wifiMaintenance)
        val c = section(wifiMaintenance, "Modifier le Wi-Fi 2,4 GHz de l'ESP32")
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

        val access = section(advanced, "Code d'accès de l'ESP32")
        add(access, label("Modifie le code personnel de la passerelle après association. Minimum 8 caractères pour protéger aussi son point d'accès Wi-Fi.", 13f, color = subdued))
        accessCodeNew = input("Nouveau code ESP32 (8 à 32 caractères)", true)
        add(access, accessCodeNew)
        accessCodeBtn = action("Changer le code de ma passerelle") { changeDeviceCode() }
        add(access, accessCodeBtn)

        add(advanced, label("BLE multi-batteries, OTA et accès distant : fonctions en développement.", 12f, color = subdued), 12)

        val enabled = false
        scanBtn.isEnabled = enabled
        saveBtn.isEnabled = enabled
        wifiBtn.isEnabled = true // Always show actionable diagnostics.
        wifiScanBtn.isEnabled = true
        accessCodeBtn.isEnabled = true
    }

    private fun state(message: String) = runOnUiThread { status.text = message }

    private fun writeFeedback(message: String) = runOnUiThread {
        lastParameterFeedback=message
        parameterFeedback.text=message
        parameterFeedback.visibility=if(currentTab==2)View.VISIBLE else View.GONE
        status.text=message
    }

    private fun wifiState(message: String) = runOnUiThread {
        wifiStatus.text = message
        status.text = message
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
                val route = when (path) {
                    "/api/v1/health" -> "/api/v1/health"
                    "/api/v1/telemetry" -> if(activeReaderSlot==1) "/api/v1/jk/telemetry"
                        else "/api/v1/jk/${activeReaderSlot}/telemetry"
                    "/api/v1/details" -> if(activeReaderSlot==1) "/api/v1/jk/details"
                        else "/api/v1/jk/${activeReaderSlot}/details"
                    else -> throw IllegalStateException("Route non autorisée sur la passerelle VPN.")
                }
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
        val recovering = savedCode.isBlank()
        val code = if (recovering) typedCode else savedCode
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
                    showTelemetry(telemetry)
                }
            } finally {
                lanConnectionPending = false
            }
        }
    }

    private fun beginBootPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 20)
            state("Autorise « Appareils Wi-Fi proches », puis relance l'association BOOT.")
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 21)
            state("Autorise la localisation pour détecter le Wi-Fi temporaire de l'ESP32.")
            return
        }
        val ssid=apName.text.toString().trim()
        if (!Regex("^SmartJK-Setup-[A-F0-9]{6}$").matches(ssid)) {
            state("Commence par « Trouver un ESP32 proche » pour sélectionner son réseau SmartJK-Setup.")
            return
        }
        val form=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(d(18),d(4),d(18),d(4))
        }
        val newCode=input("Choisis ton code JK (8 à 32 caractères)",true)
        val wifiSsid=input("Wi-Fi maison 2,4 GHz (nouveau lecteur)")
        val wifiPassword=input("Mot de passe Wi-Fi maison",true)
        wifiSsid.setText(homeSsid.text.toString())
        form.addView(label("Sur l'ESP32 déjà allumé, maintiens BOOT 4 secondes puis relâche. Tu choisiras un nouveau code, sans connaître l'ancien.",13f,color=subdued))
        add(form,newCode);add(form,wifiSsid);add(form,wifiPassword)
        add(form,label("Si l'ESP32 connaît déjà ton Wi-Fi, laisse les deux champs Wi-Fi vides. Pour une première installation vierge, saisis les deux.",12f,color=subdued))
        AlertDialog.Builder(this).setTitle("Association physique BOOT")
            .setView(form).setNegativeButton("Annuler",null)
            .setPositiveButton("Se connecter à l'ESP32") {_,_->
                val code=newCode.text.toString()
                val home=wifiSsid.text.toString().trim()
                val pass=wifiPassword.text.toString()
                if(code.length !in 8..32 || code.any{it.code !in 33..126}) {
                    state("Choisis un code de 8 à 32 caractères ASCII sans espaces.")
                } else if(home.isNotBlank() && pass.length !in 8..63) {
                    state("Le mot de passe Wi-Fi doit contenir de 8 à 63 caractères.")
                } else if(home.isBlank() && pass.isNotBlank()) {
                    state("Indique le nom du Wi-Fi ou laisse les deux champs vides.")
                } else {
                    connectBootPairing(ssid,code,home,pass)
                }
            }.show()
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
                state("Wi-Fi temporaire BOOT connecté : association en préparation…")
                async {
                    try {
                        val info=httpCall("GET","/api/v1/info")
                        val deviceId=info.optString("device_id")
                        val expected=prefs.getString("device_id","") ?: ""
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
                        state("Code prêt : maintiens BOOT une deuxième fois pendant 2 secondes, " +
                              "relâche, puis utilise le bouton « Après le 2e BOOT ».")
                    } catch(ex:Exception) {
                        bootPairTicket=0
                        state("Association BOOT impossible : "+(ex.message ?: "erreur") +
                            ". Vérifie que tu as maintenu BOOT 4 s et que le Wi-Fi temporaire est visible.")
                    }
                }
            }
            override fun onLost(network:Network) {
                if(apNetwork==network)apNetwork=null
                if(!switchingToHome)state("Réseau BOOT perdu ou délai expiré : réessaie l'association.")
            }
            override fun onUnavailable() {
                state("Réseau BOOT introuvable. Maintiens BOOT 4 s sur l'ESP32 allumé, puis réessaie.")
            }
        }
        callback=cb
        state("Recherche du réseau temporaire ouvert après BOOT… Accepte la demande Android.")
        try{connectivity.requestNetwork(req,cb,30000)}
        catch(ex:Exception){state("Connexion BOOT impossible : "+(ex.message ?: "réseau"))}
    }

    private fun commitBootPairing() {
        val ticket=bootPairTicket
        val id=bootPairDeviceId
        val code=bootPairCode
        if(ticket==0L || id.isBlank() || code.length !in 8..32 ||
            !setupTransport || apNetwork==null) {
            state("Aucune association BOOT en attente. Commence par « Associer avec BOOT ».")
            return
        }
        async {
            try {
                val check=httpCall("GET","/api/v1/pair/boot/status?ticket=$ticket")
                if(!check.optBoolean("confirmed_by_boot")) {
                    state("Seconde confirmation absente : maintiens BOOT 2 secondes puis relâche.")
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
                    state("Association physique réussie. Code mémorisé et BMS conservés. " +
                          "L'ESP32 rejoint maintenant ton Wi-Fi 2,4 GHz.")
                    uiHandler.postDelayed({ discoverHomeLan() },9000)
                }
            }catch(ex:Exception){
                state("Confirmation BOOT impossible : "+(ex.message ?: "erreur") +
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

    private fun findNearbyEsp32() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 41)
            state("Autorise la localisation pour rechercher les Wi-Fi SmartJK proches.")
            return
        }
        val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!location.isLocationEnabled) {
            state("Active la localisation Android pour rechercher les ESP32 proches (obligation du système Android).")
            return
        }
        if (!wifiManager.isWifiEnabled) {
            state("Active le Wi-Fi du téléphone avant de rechercher les ESP32.")
            return
        }
        state("Recherche des réseaux SmartJK proches…")
        try { wifiManager.startScan() } catch (_: SecurityException) {
            state("Autorisation Wi-Fi insuffisante pour rechercher les ESP32.")
            return
        }
        uiHandler.postDelayed({
            try {
                val devices = wifiManager.scanResults
                    .filter { it.SSID.matches(Regex("^SmartJK-Setup-[A-Fa-f0-9]{6}$")) }
                    .sortedByDescending { it.level }
                    .map { it.SSID }
                    .distinct()
                when (devices.size) {
                    0 -> {
                        state("Aucun point d'accès SmartJK visible. Si l'ESP32 est déjà sur la box, utilise « Rechercher sur mon Wi-Fi ». Sinon maintiens BOOT 4 secondes.")
                        discoverHomeLan()
                    }
                    1 -> useFoundEsp32(devices[0])
                    else -> AlertDialog.Builder(this)
                        .setTitle("Choisis ton ESP32")
                        .setItems(devices.toTypedArray()) { _, index -> useFoundEsp32(devices[index]) }
                        .show()
                }
            } catch (_: SecurityException) {
                state("Vérifie les autorisations de localisation et le Wi-Fi Android.")
            }
        }, 2800)
    }

    private fun useFoundEsp32(ssid: String) {
        apName.setText(ssid)
        state("ESP32 trouvé : " + ssid +
            ". Si c'est une première association ou un code oublié, " +
            "maintiens BOOT 4 s et utilise « Associer avec BOOT » dans la section 1. " +
            "Sinon, tu peux te connecter avec le code mémorisé.")
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
            telemetryStatus.text = "Connecte d'abord ton ESP32. Aucune mesure disponible."
            return
        }
        telemetryBusy = true
        io.execute {
            try {
                val reply = httpCall("GET", "/api/v1/telemetry")
                runOnUiThread { showTelemetry(reply) }
            } catch (ex: Exception) {
                if ((remoteVpnConnection || remoteGatewayConnection) && !vpnConnected()) lanVerified = false
                runOnUiThread {
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
            if (tab == 2) refreshDetails()
            return
        }
        currentTab = tab
        parameterFeedback.visibility =
            if(tab==2 && lastParameterFeedback.isNotBlank()) View.VISIBLE else View.GONE
        highlightActiveTab()
        mountedTab = -1
        lastTelemetry?.let { showTelemetry(it) }
        if (tab == 2) refreshDetails()
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
                    writeFeedback("Commande envoyée à $label : vérification de la valeur sur le BMS…")
                    var finalState="sent_unconfirmed"
                    for(i in 0 until 12) {
                        Thread.sleep(1500)
                        val path="/api/v1/jk/write-status?mac="+
                            java.net.URLEncoder.encode(mac,"UTF-8")
                        val status=httpCall("GET",path)
                        finalState=status.optString("state","sent_unconfirmed")
                        if(finalState !in setOf("queued","sent_unconfirmed")) break
                    }
                    if(finalState=="confirmed") {
                        writeFeedback("BMS confirmé : $label a été modifié et relu.")
                    } else {
                        writeFeedback("Modification NON confirmée : "+
                            JkWriteHelp.reason(finalState)+
                            ". Contrôle la valeur sur le JK avant de recommencer.")
                    }
                    lastDetailsFetchedAt=0
                    refreshDetails()
                } catch(ex:Exception) {
                    writeFeedback("Modification REFUSÉE : "+
                        JkWriteHelp.reason(ex.message ?: "erreur")+
                        ". Ne réessaie pas avant une nouvelle lecture.")
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
        readerHeader.text="Lecteur sélectionné : ESP $activeReaderSlot. " +
            "Chaque lecteur possède son propre Wi-Fi, code d'accès et deux BMS."
    }

    private fun switchReaderSlot(target:Int) {
        if(target !in 1..3 || target==activeReaderSlot) return
        if(lanConnectionPending || setupTransport) {
            state("Termine la connexion ou l'association en cours avant de changer d'ESP32.")
            return
        }
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
        selectedBmsMac=""
        parameterEditingEnabled=false
        lastTelemetry=null
        lastDetails=null
        lastDetailsFetchedAt=0L
        dashboardMacs=emptyList()
        mountedTab=-1
        telemetryPanel.removeAllViews()
        status.text="ESP32 JK $target sélectionné. Connecte cette passerelle."
        telemetryStatus.text="En attente des mesures de l'ESP32 JK $target."
        lanIp.setText(localHost)
        apName.setText(prefs.getString("setup_ssid","") ?: "")
        setupCode.text.clear()
        refreshReaderSelector()
        if(selectedDeviceId.isNotBlank() && storedCode().isNotBlank()) {
            when(selectedRoute()) {
                JkTransportPolicy.Route.GATEWAY_VPN -> connectGatewayRemote()
                JkTransportPolicy.Route.LOCAL_WIFI -> connectHomeLan(false)
                else -> state("ESP32 $target : configure ou choisis le mode de connexion.")
            }
        }
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
        AlertDialog.Builder(this)
            .setTitle("Attention : modifications des paramètres")
            .setMessage("Tu entres dans les modifications de paramètres du BMS. Des valeurs incorrectes peuvent endommager la batterie ou neutraliser des protections. Chaque changement demandera une confirmation individuelle ; les protections critiques exigent une confirmation supplémentaire. Aucune modification automatique.")
            .setNegativeButton("Annuler",null)
            .setPositiveButton("Activer le mode modification") { _,_ ->
                parameterEditingEnabled=true
                mountedTab=-1
                lastTelemetry?.let { showTelemetry(it) }
            }.show()
    }

    private fun selectBms(mac: String) {
        parameterEditingEnabled=false
        selectedBmsMac = mac
        mountedBmsMac = ""
        mountedTab = -1
        lastTelemetry?.let { showTelemetry(it) }
        if (currentTab == 2 && mac.isNotBlank()) refreshDetails()
    }

    private fun renderBmsTabs(macs: List<String>, showGlobal: Boolean) {
        bmsTabRow.removeAllViews()
        if (!showGlobal) {
            bmsTabStrip.visibility = View.GONE
            return
        }
        bmsTabStrip.visibility = View.VISIBLE
        fun button(caption: String, targetMac: String) {
            val active = selectedBmsMac == targetMac
            val item = action(caption) { selectBms(targetMac) }
            item.textSize = 12f
            item.backgroundTintList = ColorStateList.valueOf(
                if (active) mint else Color.rgb(38,57,77))
            item.setTextColor(if (active) background else normal)
            bmsTabRow.addView(item, LinearLayout.LayoutParams(d(103),d(43)).apply{
                rightMargin=d(6)
            })
        }
        button("Accueil", "")
        for ((index,mac) in macs.withIndex()) button(displayBmsName(mac,"BMS ${index+1}"),mac)
    }

    private fun showTelemetry(response: JSONObject) {
        lastTelemetry = response
        val devices = response.optJSONArray("devices")
        if (devices == null || devices.length() == 0) {
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
        val multi = macs.size > 1
        if (!multi) selectedBmsMac = macs.firstOrNull() ?: ""
        else if (selectedBmsMac.isNotBlank() && selectedBmsMac !in macs)
            selectedBmsMac = ""
        val global = multi && selectedBmsMac.isBlank()
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
            renderBmsTabs(macs,multi)
            if(global) {
                globalOverview = JkGlobalOverview(this)
                telemetryPanel.addView(globalOverview,LinearLayout.LayoutParams(-1,-2))
            } else {
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
        if(global) globalOverview?.bind(devices,
            { mac -> selectBms(mac) },
            { mac,index -> displayBmsName(mac,"BMS ${index+1}") })
        else {
            for(i in 0 until devices.length()){
                val item=devices.optJSONObject(i) ?: continue
                val mac=item.optString("mac")
                if(mac != selectedBmsMac) continue
                when(currentTab) {
                    0 -> dashboardCards[mac]?.bind(i,item)
                    1 -> advancedCards[mac]?.measurements(i,item)
                    2 -> advancedCards[mac]?.settings(i,detailForMac(mac),item,
                        parameterEditingEnabled,
                        { toggleParameterEditMode() }) { param ->
                        editJkParameter(mac,param)
                    }
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
        telemetryStatus.text = "$liveCount/${devices.length()} JK en ligne · $sectionTitle" +
            if (diagnostics.isEmpty()) "" else "\n" + diagnostics.joinToString("\n")
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(telemetryTicker)
        uiHandler.postDelayed(telemetryTicker, 500)
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
        super.onDestroy()
    }
}
