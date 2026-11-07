package fr.smartenergy.android

import android.Manifest
import android.app.Activity
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

/**
 * Smart Energy Android 0.1.3-dev.
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
    private lateinit var scanBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var wifiBtn: Button
    private lateinit var wifiStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawUi()
        selectedDeviceId = prefs.getString("device_id", "") ?: ""
        val savedIp = prefs.getString("local_ip", "") ?: ""
        if (allowedLocalIp(savedIp) && storedCode().isNotBlank()) {
            localHost = savedIp
            lanIp.setText(savedIp)
            connectHomeLan()
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
        lanDiscoverBtn = action("Rechercher automatiquement la passerelle") { discoverHomeLan() }
        add(lan, lanDiscoverBtn)
        add(lan, label("La recherche utilise mDNS ; si elle échoue, renseigne une fois l'adresse IP vue dans les journaux ESP32.", 12f, color = subdued))

        val a = section(root, "2. Première association / dépannage")

        add(a, label("À utiliser seulement lors du premier appairage ou si le Wi-Fi de la box a changé.", color = subdued))
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

        val c = section(root, "4. Changer le Wi-Fi de la maison")
        homeSsid = input("Nom de ton réseau Wi-Fi (SSID)")
        homePassword = input("Mot de passe Wi-Fi", true)
        add(c, homeSsid)
        add(c, homePassword)
        wifiBtn = action("Enregistrer / modifier le Wi-Fi") { sendHomeWifi() }
        add(c, wifiBtn)
        wifiStatus = label("Si ton ESP32 est déjà connecté à ta box, inutile de remplir cette section. Ce bouton sert à changer le réseau Wi-Fi.", 13f, color = subdued)
        add(c, wifiStatus)
        add(c, label("Réseau local de confiance uniquement (HTTP non chiffré dans ce prototype). Le firmware doit être en v0.1.3-dev pour modifier le Wi-Fi depuis la box.", 12f, color = subdued))

        val d = section(root, "5. Tableau de bord JK")
        add(d, label("En attente du décodeur JK V19. Aucune valeur fictive n'est affichée.", color = subdued))
        val e = section(root, "Prochainement")
        add(e, label("Lecture SOC/cellules · OTA ESP32 · notifications APK · accès distant sécurisé.", color = subdued))

        val enabled = false
        scanBtn.isEnabled = enabled
        saveBtn.isEnabled = enabled
        wifiBtn.isEnabled = true // Toujours cliquable pour expliquer les prérequis.
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
        val password = setupCode.text.toString().trim().uppercase().ifBlank { storedCode() }
        if (!Regex("^SmartJK-Setup-[A-F0-9]{6}$").matches(ssid) || password.length != 12) {
            state("Vérifie le SSID SmartJK-Setup-XXXXXX et son code à 12 caractères.")
            return
        }
        releaseConnection()
        lanVerified = false
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
                apNetwork = null
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
        apNetwork = null
        callback?.let {
            try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        callback = null
    }

    private fun httpCall(method: String, path: String, payload: JSONObject? = null): JSONObject {
        require(path.startsWith("/api/v1/"))
        val network = apNetwork
        val conn = if (network != null) {
            network.openConnection(URL("http://192.168.4.1" + path)) as HttpURLConnection
        } else {
            val host = localHost
            if (!allowedLocalIp(host)) throw IllegalStateException("Associe d'abord l'ESP32 ou indique une adresse locale.")
            URL("http://" + host + path).openConnection() as HttpURLConnection
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
        } finally { conn.disconnect() }
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
            runOnUiThread {
                scanBtn.isEnabled = true
                saveBtn.isEnabled = true
                wifiBtn.isEnabled = true
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
                if (found || !serviceInfo.serviceName.startsWith("smartjk-", true)) return
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
    }

    private fun sendHomeWifi() {
        val ssid = homeSsid.text.toString().trim()
        val pass = homePassword.text.toString()
        if (ssid.isBlank() || ssid.length > 32 || pass.length !in 8..63) {
            wifiState("Renseigne un SSID et un mot de passe Wi-Fi de 8 à 63 caractères.")
            return
        }
        val viaSetup = apNetwork != null
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
                    if (!info.optString("version").startsWith("0.1.3")) {
                        throw IllegalStateException("Le firmware ESP32 doit être en v0.1.3 pour changer le Wi-Fi depuis le réseau de la maison. L'ancien firmware ne le permet qu'en mode configuration.")
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

    override fun onDestroy() {
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
