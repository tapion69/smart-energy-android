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
 * Smart Energy Android 0.2.3-dev (JK overview / icon / VPN gateway).
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
    private val chosen = mutableSetOf<String>()
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
    private var currentTab = 0 // 0 overview, 1 extended measurements, 2 read-only settings
    private var mountedTab = -1
    private var lastTelemetry: JSONObject? = null
    private var lastDetails: JSONObject? = null
    private var lastDetailsFetchedAt = 0L
    @Volatile private var detailsBusy = false
    private lateinit var tabOverview: Button
    private lateinit var tabExtended: Button
    private lateinit var tabSettings: Button
    private lateinit var telemetryStatus: TextView
    @Volatile private var telemetryBusy = false
    private val telemetryTicker = object : Runnable {
        override fun run() {
            if (!lanVerified && !lanConnectionPending && !setupTransport && vpnConnected()) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastRemoteAttempt >= 20000L) {
                    if (prefs.getBoolean("remote_gateway_enabled", false) &&
                        allowedTailnetIp(prefs.getString("remote_gateway_ip", "") ?: "") &&
                        storedGatewayToken().isNotBlank()) {
                        lastRemoteAttempt = now
                        connectGatewayRemote()
                    } else if (prefs.getBoolean("remote_vpn_enabled", false) &&
                               storedCode().isNotBlank()) {
                        lastRemoteAttempt = now
                        connectHomeLan(true)
                    }
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
        if (prefs.getBoolean("remote_gateway_enabled", false) && vpnConnected() &&
            allowedTailnetIp(prefs.getString("remote_gateway_ip", "") ?: "") &&
            storedGatewayToken().isNotBlank()) {
            connectGatewayRemote()
        } else if (storedCode().isNotBlank()) {
            if (allowedLocalIp(savedIp)) {
                localHost = savedIp
                lanIp.setText(savedIp)
                connectHomeLan(prefs.getBoolean("remote_vpn_enabled", false) && vpnConnected())
            } else {
                discoverHomeLan()
            }
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
        val active = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(active)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
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
        Regex("^[A-Za-z0-9_-]{20,128}$").matches(token)

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
        add(root, label("Tableau de bord JK", 25f, true), 18)
        status = label("Connexion à la passerelle…", 13f, false, subdued)
        add(root, status, 8)
        telemetryStatus = label("En attente des mesures réelles du JK", 13f, false, subdued)
        add(root, telemetryStatus, 10)
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
        add(root, tabs, 8)
        highlightActiveTab()
        telemetryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(root, telemetryPanel, 8)
        add(root, action("⟳ Actualiser les mesures") { refreshTelemetry() }, 8)
        add(root, label("Données expérimentales en lecture seule. Ne pas utiliser pour une protection batterie.", 12f, color = subdued), 8)
        val advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (storedCode().isBlank()) View.VISIBLE else View.GONE
        }
        add(root, action("⚙ Configuration et association") {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }, 20)
        root.addView(advanced)

        val lan = section(advanced, "Connexion quotidienne — Wi-Fi maison")
        add(lan, label("L’application mémorise le code après association. Si une réinstallation l’a effacé, renseigne le code dans la section 2 puis clique ici : pas besoin de reconfigurer la box.", color = subdued))
        lanIp = input("Adresse IP locale de l'ESP32 (ex. 192.168.1.50)")
        lanIp.setText(prefs.getString("local_ip", "") ?: "")
        add(lan, lanIp)
        lanConnectBtn = action("Se connecter à ma passerelle") { connectHomeLan() }
        add(lan, lanConnectBtn)
        physicalRecoverBtn = action("Code perdu ? Réassocier avec BOOT") { recoverWithBoot() }
        add(lan, physicalRecoverBtn)
        add(lan, label("Avec le firmware ESP32 v0.1.8-dev : maintiens BOOT 4 secondes pour autoriser une nouvelle association, sans USB et sans effacer le Wi-Fi/JK.", 12f, color = subdued))
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
        gatewayTokenEdit = input("Code secret de la passerelle VPN (pas celui du JK)", true)
        gatewayTokenEdit.hint = if (storedGatewayToken().isNotBlank())
            "Code personnel déjà mémorisé — laisser vide" else "Ton X-Gateway-Token personnel"
        add(remote, gatewayTokenEdit)
        add(remote, label("Le code reste chiffré avec Android Keystore. N'indique jamais une adresse Internet publique. Notre firmware autorise uniquement les lectures JK.", 12f, color = subdued))
        add(remote, action("Enregistrer et tester mon ESP32 VPN") {
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

        add(a, label("Si l’APK a perdu son association, saisis ici le code original de l’ESP32 puis clique sur « Se connecter à ma passerelle » dans la section 1. Inutile de modifier le Wi-Fi ou le JK.", color = subdued))
        apName = input("SmartJK-Setup-XXXXXX")
        setupCode = input("Code unique de l'ESP32 (vide = code mémorisé)", true)
        apName.setText(prefs.getString("setup_ssid", "") ?: "")
        add(a, apName)
        add(a, setupCode)
        add(a, action("Connecter l'ESP32") { connectSetupAp() }, 12)
        add(a, label("Android demandera l'autorisation de rejoindre ce Wi-Fi temporaire. Pas besoin d'aller dans les paramètres Wi-Fi.", 12f, color = subdued))

        val b = section(advanced, "3. Détecter les JK BMS")
        add(b, label("La recherche Bluetooth est exécutée par l'ESP32, pas par le téléphone. Vérifie la MAC avant de sélectionner un appareil.", 13f, color = subdued))
        scanBtn = action("Rechercher les appareils BLE") { scanBle() }
        add(b, scanBtn)
        scanPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(b, scanPanel)
        saveBtn = action("Sauvegarder les batteries choisies") { saveSelection() }
        add(b, saveBtn)
        add(b, label("Pour ce jalon, la sélection est conservée après redémarrage ; la lecture des mesures JK sera ajoutée ensuite.", 12f, color = subdued))

        val c = section(advanced, "4. Connecter l'ESP32 à un réseau Wi-Fi 2,4 GHz")
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
        add(c, label("Réseau local de confiance uniquement (HTTP non chiffré dans ce prototype). Le firmware doit être en v0.1.3-dev pour modifier le Wi-Fi depuis la box.", 12f, color = subdued))

        val access = section(advanced, "5. Code d'accès de l'ESP32")
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
                    "/api/v1/telemetry" -> "/api/v1/jk/telemetry"
                    "/api/v1/details" -> "/api/v1/jk/details"
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
            } else if (path != "/api/v1/info") {
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
            if (code !in 200..299) throw IllegalStateException(json.optString("error", "HTTP $code"))
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
        val vpnActive = connectivity.activeNetwork?.let {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        if (vpnActive && (ex is java.net.SocketException ||
                           ex is java.net.UnknownHostException ||
                           ex is java.net.SocketTimeoutException)) {
            return IllegalStateException(
                if (remoteGatewayConnection)
                    "Passerelle ESP32 VPN injoignable. Vérifie Tailscale et l'état du deuxième ESP32."
                else if (remoteVpnConnection)
                    "VPN actif mais réseau de la maison inaccessible. Vérifie la route du réseau privé."
                else
                    "Connexion locale bloquée par le VPN. Autorise le réseau local dans ses paramètres ou désactive-le pour ce test.", ex
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
                chosen.clear()
                for (i in 0 until addresses.length()) chosen.add(addresses.optString(i))
                prefs.edit()
                    .putString("local_ip", ip)
                    .putString("paired_bms_json", addresses.toString())
                    .apply()
                lanVerified = true
                lastLanFirmwareVersion = info.optString("version")
                runOnUiThread {
                    setupCode.text.clear()
                    scanBtn.isEnabled = true
                    saveBtn.isEnabled = true
                    wifiBtn.isEnabled = !remote
                    if (remote) {
                        remoteStatus.text = "VPN privé : passerelle accessible depuis le téléphone."
                    }
                    state((if (remote) "Accès distant VPN confirmé. " else
                        if (recovering) "Code mémorisé. " else "Connecté automatiquement. ") +
                        id + " · " + chosen.size + " JK mémorisé(s).")
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
            state("Code VPN absent : saisis le code de 20 à 128 caractères configuré dans ton ESP32 VPN.")
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
        state("Vérification de l'ESP32 VPN et des batteries associées…")
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

    private fun recoverWithBoot() {
        val ip = lanIp.text.toString().trim()
        if (!allowedLocalIp(ip)) {
            state("Renseigne l'adresse IP locale de l'ESP32 dans la section 1.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Réassocier sans l'ancien code")
            .setMessage("1. Assure-toi que l'ESP32 utilise le firmware 0.1.8-dev.\n" +
                "2. Sur l'ESP32 allumé, maintiens BOOT 4 secondes, puis relâche (ne le maintiens pas 10 secondes).\n" +
                "3. Dans les 45 secondes, appuie sur « Réassocier » ici.\n\n" +
                "L'APK créera un nouveau code aléatoire à la place de l'ancien. Le Wi-Fi et les JK enregistrés resteront inchangés.\n\n" +
                "Cette fonction expérimentale est limitée au réseau local de confiance.")
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Réassocier") { _, _ -> performPhysicalRecovery(ip) }
            .show()
    }

    private fun randomDeviceCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString {
            repeat(20) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun performPhysicalRecovery(ip: String) {
        physicalRecoverBtn.isEnabled = false
        lanVerified = false
        releaseConnection()
        localHost = ip
        state("Vérification de la passerelle et de la confirmation physique BOOT…")
        async {
            try {
                val info = httpCall("GET", "/api/v1/info")
                val id = info.optString("device_id")
                val saved = prefs.getString("device_id", "") ?: ""
                if (info.optString("product") != "smart-jk-esp32" ||
                    !Regex("^smartjk-[A-F0-9]{6}$", RegexOption.IGNORE_CASE).matches(id) ||
                    (saved.isNotBlank() && saved != id) ||
                    !info.optBoolean("wifi_connected")) {
                    throw IllegalStateException("L'ESP32 ne correspond pas à la passerelle attendue.")
                }
                if (!info.optBoolean("physical_recovery")) {
                    throw IllegalStateException("Firmware trop ancien. Une mise à jour ESP32 v0.1.8-dev est nécessaire avant cette fonction.")
                }
                val generated = randomDeviceCode()
                val payload = JSONObject().put("device_id", id).put("new_code", generated)
                var submitted = false
                try {
                    val response = httpCall("POST", "/api/v1/pair/recover", payload)
                    if (!response.optBoolean("paired") || response.optString("device_id") != id) {
                        throw IllegalStateException("La réassociation n'a pas été confirmée par l'ESP32.")
                    }
                    submitted = true
                } catch (ex: Exception) {
                    // POST response may have been lost after secret rotation.
                    // Recover only if the *new* code proves accepted by the ESP32.
                    activeSetupCode = generated
                    try {
                        httpCall("GET", "/api/v1/bms")
                        submitted = true
                    } catch (_: Exception) {
                        throw ex
                    }
                }
                if (!submitted) throw IllegalStateException("Réassociation non confirmée.")
                activeSetupCode = generated
                // Persist only after receiving device proof using the new secret.
                val configured = httpCall("GET", "/api/v1/bms")
                val macs = configured.optJSONArray("bms") ?: JSONArray()
                savePairing(id, "SmartJK-Setup-" + id.substringAfter("-").uppercase(Locale.ROOT), generated)
                prefs.edit().putString("local_ip", ip).apply()
                chosen.clear()
                for (index in 0 until macs.length()) chosen.add(macs.optString(index))
                lanVerified = true
                lastLanFirmwareVersion = info.optString("version")
                runOnUiThread {
                    setupCode.text.clear()
                    scanBtn.isEnabled = true
                    saveBtn.isEnabled = true
                    state("ESP32 réassocié avec confirmation physique. Nouveau code sécurisé mémorisé ; " +
                        chosen.size + " JK conservé(s).")
                    refreshTelemetry()
                }
            } catch (ex: Exception) {
                state("Réassociation non effectuée : " +
                    (ex.message ?: ex.javaClass.simpleName) +
                    ". Vérifie le firmware et maintiens BOOT 4 secondes avant de relancer.")
            } finally {
                runOnUiThread { physicalRecoverBtn.isEnabled = true }
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
        if (storedCode().isNotBlank() && prefs.getString("setup_ssid", "") == ssid) {
            state("ESP32 déjà associé retrouvé : connexion en cours…")
            connectSetupAp()
        } else {
            state("ESP32 trouvé : " + ssid + ". Entre son code unique une seule fois dans la section 2, puis clique sur « Connecter l'ESP32 ».")
        }
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

    private fun scanBle() = async {
        state("Scan BLE en cours sur l'ESP32 (environ 5 secondes)…")
        val response = httpCall("GET", "/api/v1/bms/scan")
        val devices = response.getJSONArray("devices")
        runOnUiThread {
            val saved = chosen.toSet()
            scanChoices.clear()
            scanPanel.removeAllViews()
            if (devices.length() == 0) {
                add(scanPanel, label("Aucun périphérique BLE trouvé.", color = subdued))
            }
            for (i in 0 until devices.length()) {
                val device = devices.getJSONObject(i)
                val mac = device.optString("mac")
                val name = device.optString("name").ifBlank { "Périphérique sans nom" }
                val rssi = device.optInt("rssi")
                val check = CheckBox(this).apply {
                    text = "$name\n$mac · $rssi dBm"
                    setTextColor(normal)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) chosen.add(mac) else chosen.remove(mac)
                    }
                }
                if (saved.contains(mac)) check.isChecked = true
                add(scanPanel, check)
                scanChoices.add(check)
            }
            state("${devices.length()} appareils BLE détectés. Choisis uniquement tes JK.")
        }
    }

    private fun saveSelection() = async {
        val array = JSONArray()
        chosen.toList().sorted().forEach { array.put(it) }
        val json = JSONObject().put("bms", array)
        val response = httpCall("PUT", "/api/v1/bms", json)
        state("${response.optInt("saved")} adresses enregistrées dans l'ESP32 (persistantes).")
        refreshTelemetry()
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
        if (tab == currentTab) {
            if (tab == 2) refreshDetails()
            return
        }
        currentTab = tab
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

    private fun showTelemetry(response: JSONObject) {
        lastTelemetry = response
        val devices = response.optJSONArray("devices")
        if (devices == null) {
            telemetryStatus.text = "Ce firmware ne prend pas en charge les mesures JK."
            telemetryPanel.removeAllViews()
            dashboardCards.clear()
            advancedCards.clear()
            dashboardMacs = emptyList()
            return
        }
        if (devices.length() == 0) {
            telemetryStatus.text = "Aucun JK enregistré. Ouvre « Configuration et association »."
            telemetryPanel.removeAllViews()
            dashboardCards.clear()
            advancedCards.clear()
            dashboardMacs = emptyList()
            return
        }
        val macs = (0 until devices.length()).mapNotNull { i ->
            devices.optJSONObject(i)?.optString("mac")?.takeIf { it.isNotBlank() }
        }
        if (macs != dashboardMacs || mountedTab != currentTab) {
            dashboardMacs = macs
            mountedTab = currentTab
            telemetryPanel.removeAllViews()
            dashboardCards.clear()
            advancedCards.clear()
            for (mac in macs) {
                if (currentTab == 0) {
                    val card = BatteryDashboardCard(this)
                    dashboardCards[mac] = card
                    telemetryPanel.addView(card, LinearLayout.LayoutParams(-1, -2))
                } else {
                    val card = BatteryAdvancedCard(this)
                    advancedCards[mac] = card
                    telemetryPanel.addView(card, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
        var validCount = 0
        for (i in 0 until devices.length()) {
            val item = devices.optJSONObject(i) ?: continue
            val mac = item.optString("mac")
            if (item.optBoolean("available")) validCount++
            when (currentTab) {
                0 -> dashboardCards[mac]?.bind(i, item)
                1 -> advancedCards[mac]?.measurements(i, item)
                2 -> advancedCards[mac]?.settings(i, detailForMac(mac), item)
            }
        }
        val suffix = when(currentTab) {
            0 -> "Vue d'ensemble"
            1 -> "Mesures détaillées"
            else -> "Paramètres BMS · lecture seule"
        }
        telemetryStatus.text = validCount.toString() + "/" + devices.length() +
            " JK en ligne · " + suffix
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
