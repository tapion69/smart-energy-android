package fr.smartenergy.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Locale

/**
 * Smart Energy Android 0.1.6-dev.
 * Initial provisioning over ESP32's setup AP, subsequent management over home Wi-Fi.
 * Credentials kept encrypted in Android Keystore-backed local preferences.
 * DEVELOPMENT-ONLY HTTP local transport; NOT safe for remote/public use.
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
    @Volatile private var setupTransport = false
    @Volatile private var lastLanFirmwareVersion = ""
    @Volatile private var selectedDeviceId = ""
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var lanIp: EditText
    private lateinit var lanConnectBtn: Button
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
    private lateinit var telemetryStatus: TextView
    @Volatile private var telemetryBusy = false
    private val telemetryTicker = object : Runnable {
        override fun run() {
            if (lanVerified || (setupTransport && apNetwork != null)) refreshTelemetry()
            uiHandler.postDelayed(this, 8000)
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
        if (storedCode().isNotBlank()) {
            if (allowedLocalIp(savedIp)) {
                localHost = savedIp
                lanIp.setText(savedIp)
                connectHomeLan()
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

        add(root, label("SMART ENERGY", 13f, true, mint))
        add(root, label("Mes passerelles JK", 25f, true))
        add(root, label("Wi-Fi maison en priorité. Configuration initiale seulement si nécessaire.", 13f, color = subdued))
        status = label("ESP32 non connecté", 14f, true)
        add(root, status, 14)

        val lan = section(root, "1. Connexion quotidienne — Wi-Fi maison")
        add(lan, label("Après la première association, l'application garde la passerelle et son code en mémoire sécurisée.", color = subdued))
        lanIp = input("Adresse IP locale de l'ESP32 (ex. 192.168.1.50)")
        lanIp.setText(prefs.getString("local_ip", "") ?: "")
        add(lan, lanIp)
        lanConnectBtn = action("Se connecter à ma passerelle") { connectHomeLan() }
        add(lan, lanConnectBtn)
        lanDiscoverBtn = action("Rechercher sur mon Wi-Fi") { discoverHomeLan() }
        add(lan, lanDiscoverBtn)
        autoEspBtn = action("Trouver un ESP32 proche") { findNearbyEsp32() }
        add(lan, autoEspBtn)
        add(lan, label("La recherche utilise mDNS ; si elle échoue, renseigne une fois l'adresse IP vue dans les journaux ESP32.", 12f, color = subdued))

        val a = section(root, "2. Première association / dépannage")

        add(a, label("Le nom de l’ESP32 peut être trouvé automatiquement ci-dessus. Son code unique est à entrer une seule fois lors de la première association.", color = subdued))
        apName = input("SmartJK-Setup-XXXXXX")
        setupCode = input("Code unique de l'ESP32 (vide = code mémorisé)", true)
        apName.setText(prefs.getString("setup_ssid", "") ?: "")
        add(a, apName)
        add(a, setupCode)
        add(a, action("Connecter l'ESP32") { connectSetupAp() }, 12)
        add(a, label("Android demandera l'autorisation de rejoindre ce Wi-Fi temporaire. Pas besoin d'aller dans les paramètres Wi-Fi.", 12f, color = subdued))

        val b = section(root, "3. Détecter les JK BMS")
        add(b, label("La recherche Bluetooth est exécutée par l'ESP32, pas par le téléphone. Vérifie la MAC avant de sélectionner un appareil.", 13f, color = subdued))
        scanBtn = action("Rechercher les appareils BLE") { scanBle() }
        add(b, scanBtn)
        scanPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(b, scanPanel)
        saveBtn = action("Sauvegarder les batteries choisies") { saveSelection() }
        add(b, saveBtn)
        add(b, label("Pour ce jalon, la sélection est conservée après redémarrage ; la lecture des mesures JK sera ajoutée ensuite.", 12f, color = subdued))

        val c = section(root, "4. Connecter l'ESP32 à un réseau Wi-Fi 2,4 GHz")
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

        val access = section(root, "5. Code d'accès de l'ESP32")
        add(access, label("Modifie le code personnel de la passerelle après association. Minimum 8 caractères pour protéger aussi son point d'accès Wi-Fi.", 13f, color = subdued))
        accessCodeNew = input("Nouveau code ESP32 (8 à 32 caractères)", true)
        add(access, accessCodeNew)
        accessCodeBtn = action("Changer le code de ma passerelle") { changeDeviceCode() }
        add(access, accessCodeBtn)

        val d = section(root, "6. Tableau de bord JK — mesures expérimentales")
        telemetryStatus = label("Sélectionne ton JK et connecte-toi à l'ESP32. Les mesures seront visibles uniquement si une trame valide est reçue.", 13f, color = subdued)
        add(d, telemetryStatus)
        add(d, action("Actualiser les mesures JK") { refreshTelemetry() })
        telemetryPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(d, telemetryPanel)
        add(d, label("Lecture seule. Format JK02 24S/32S en cours de validation : ne pas utiliser pour une protection batterie.", 12f, color = subdued))
        val e = section(root, "Prochainement")
        add(e, label("Validation sur tes JK V19 · résistance des cellules · OTA ESP32 · accès distant sécurisé.", color = subdued))

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
            conn.setRequestProperty("X-Setup-Code", activeSetupCode)
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
                "Android refuse l'accès au réseau local (EPERM). Désactive temporairement le VPN et l'option « Bloquer les connexions sans VPN », " +
                "vérifie que le téléphone est sur le Wi-Fi de la maison, puis relance Smart Energy.", ex
            )
        }
        val vpnActive = connectivity.activeNetwork?.let {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        if (vpnActive && (ex is java.net.SocketException ||
                           ex is java.net.UnknownHostException ||
                           ex is java.net.SocketTimeoutException)) {
            return IllegalStateException(
                "Connexion locale inaccessible avec le VPN actif. Autorise le réseau local dans ton VPN, ou désactive-le temporairement pour ce test.", ex
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

    private fun connectHomeLan() {
        lanVerified = false
        lastLanFirmwareVersion = ""
        releaseConnection()
        val ip = lanIp.text.toString().trim()
        if (!allowedLocalIp(ip)) {
            state("Indique une adresse IP privée de ta passerelle (ex. 192.168.1.50).")
            return
        }
        val code = storedCode()
        if (code.isBlank()) {
            state("Il faut associer l'ESP32 une dernière fois pour mémoriser son code.")
            return
        }
        activeSetupCode = code
        localHost = ip
        state("Connexion locale à l'ESP32…")
        async {
            val info = httpCall("GET", "/api/v1/info")
            val returnedId = info.optString("device_id")
            val savedId = prefs.getString("device_id", "") ?: ""
            if (info.optString("product") != "smart-jk-esp32" || returnedId != savedId ||
                !info.optBoolean("wifi_connected")) {
                localHost = ""
                state("La passerelle ne correspond pas à l'appareil associé, ou n'est pas sur le Wi-Fi maison.")
                return@async
            }
            val configured = httpCall("GET", "/api/v1/bms")
            val storedBms = configured.optJSONArray("bms") ?: JSONArray()
            chosen.clear()
            for (i in 0 until storedBms.length()) chosen.add(storedBms.optString(i))
            prefs.edit().putString("local_ip", ip).apply()
            lanVerified = true
            lastLanFirmwareVersion = info.optString("version")
            runOnUiThread {
                scanBtn.isEnabled = true
                saveBtn.isEnabled = true
                wifiBtn.isEnabled = true
                refreshTelemetry()
                state("Connecté automatiquement à " + returnedId + " (" + ip + "). " +
                    chosen.size + " batteries mémorisées.")
            }
        }
    }

    private fun discoverHomeLan() {
        val code = storedCode()
        if (code.isBlank()) {
            state("Associe d'abord ton ESP32 une seule fois pour enregistrer son code.")
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
                runOnUiThread {
                    telemetryStatus.text = "Lecture JK impossible : " +
                        (ex.message ?: ex.javaClass.simpleName)
                }
            } finally {
                telemetryBusy = false
            }
        }
    }

    private fun showTelemetry(response: JSONObject) {
        telemetryPanel.removeAllViews()
        val devices = response.optJSONArray("devices")
        if (devices == null) {
            telemetryStatus.text = if (response.optString("reason") == "jk_decoder_not_implemented") {
                "Le firmware ESP32 ne dispose pas encore du lecteur JK. La mise à jour 0.1.6-dev est nécessaire."
            } else {
                "Lecteur JK non disponible sur ce firmware."
            }
            return
        }
        if (devices.length() == 0) {
            telemetryStatus.text = "Aucune batterie JK mémorisée dans l'ESP32."
            return
        }
        telemetryStatus.text = "${devices.length()} batterie(s) configurée(s). Mise à jour automatique toutes les 8 secondes."
        for (i in 0 until devices.length()) {
            val item = devices.optJSONObject(i) ?: continue
            val mac = item.optString("mac")
            val available = item.optBoolean("available")
            val title = label("JK " + (i + 1) + " · " + mac, 15f, true, mint)
            add(telemetryPanel, title, 12)
            if (!available) {
                add(telemetryPanel, label(readerStatus(item.optString("state")), 13f, color = subdued))
                val errors = item.optInt("bad_crc_count") + item.optInt("rejected_measurements")
                if (errors > 0) add(telemetryPanel, label("Trames invalides ou non reconnues : " + errors, 12f, color = subdued))
                continue
            }
            val m = item.optJSONObject("values") ?: continue
            val soc = m.optInt("soc_pct")
            val voltage = formatValue(m.optDouble("voltage_v"))
            val current = formatValue(m.optDouble("current_a"))
            val power = formatValue(m.optDouble("power_w"), 1)
            add(telemetryPanel, label("$soc % SOC  ·  $voltage V", 21f, true))
            add(telemetryPanel, label("$current A  ·  $power W", 15f))
            val min = formatValue(m.optDouble("cell_min_v"), 3)
            val max = formatValue(m.optDouble("cell_max_v"), 3)
            add(telemetryPanel, label("Cellules : " + m.optInt("cell_count") +
                "  |  min. " + min + " V  |  max. " + max + " V"))
            add(telemetryPanel, label("Écart : " + m.optInt("cell_delta_mv") +
                " mV  ·  Cycles : " + m.optInt("cycles")))
            val tempLine = listOf(
                Pair("T1", "temp1_c"), Pair("T2", "temp2_c"), Pair("MOS", "mos_temp_c")
            ).joinToString("  ·  ") { (title, key) ->
                title + " : " + (if (m.has(key) && !m.isNull(key))
                    formatValue(m.optDouble(key), 1) + " °C" else "indisponible")
            }
            add(telemetryPanel, label(tempLine, 12f, color = subdued))
            add(telemetryPanel, label("Format " + m.optString("frame_layout") +
                " · Mesure datant de " + item.optInt("age_seconds") + " s",
                12f, color = subdued))
            val cells = m.optJSONArray("cells") ?: JSONArray()
            for (index in 0 until cells.length()) {
                val cell = cells.optJSONObject(index) ?: continue
                add(telemetryPanel, label("Cellule " + cell.optInt("number") +
                    "   " + formatValue(cell.optDouble("voltage_v"), 3) + " V",
                    13f, color = subdued), 2)
            }
            add(telemetryPanel, label("Données expérimentales, ne pas utiliser pour commander le BMS.",
                12f, color = subdued), 8)
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(telemetryTicker)
        uiHandler.postDelayed(telemetryTicker, 3000)
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
