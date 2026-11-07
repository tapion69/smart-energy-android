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
 * Smart Energy Android 0.1.0-dev.
 * Real provisioning + BLE scan from ESP32, NO mock measurements.
 * Only communicates with 192.168.4.1 while the handset is on the ESP32 setup AP.
 * This is a development-only local HTTP transport; NOT a production security design.
 */
class MainActivity : Activity() {
    private val background = Color.rgb(10, 18, 29)
    private val panel = Color.rgb(24, 36, 54)
    private val normal = Color.rgb(239, 246, 255)
    private val subdued = Color.rgb(162, 176, 197)
    private val mint = Color.rgb(58, 212, 157)
    private val io = Executors.newSingleThreadExecutor()
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    @Volatile private var apNetwork: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var switchingToHome = false
    @Volatile private var activeSetupCode = ""
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawUi()
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
        add(root, label("Associer un ESP32 JK", 25f, true))
        add(root, label("Version développeur · Configuration réelle, sans Home Assistant.", 13f, color = subdued))
        status = label("ESP32 non connecté", 14f, true)
        add(root, status, 14)

        val a = section(root, "1. Connecter l'ESP32")
        add(a, label("SSID et code affichés par le moniteur série de l'ESP32 neuf. Un code propre à chaque carte.", color = subdued))
        apName = input("SmartJK-Setup-XXXXXX")
        setupCode = input("Code ESP32 (12 caractères)", true)
        add(a, apName)
        add(a, setupCode)
        add(a, action("Connecter l'ESP32") { connectSetupAp() }, 12)
        add(a, label("Android demandera l'autorisation de rejoindre ce Wi-Fi temporaire. Pas besoin d'aller dans les paramètres Wi-Fi.", 12f, color = subdued))

        val b = section(root, "2. Détecter les JK BMS")
        add(b, label("La recherche Bluetooth est exécutée par l'ESP32, pas par le téléphone. Vérifie la MAC avant de sélectionner un appareil.", 13f, color = subdued))
        scanBtn = action("Rechercher les appareils BLE") { scanBle() }
        add(b, scanBtn)
        scanPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(b, scanPanel)
        saveBtn = action("Sauvegarder les batteries choisies") { saveSelection() }
        add(b, saveBtn)
        add(b, label("Pour ce jalon, la sélection est conservée après redémarrage ; la lecture des mesures JK sera ajoutée ensuite.", 12f, color = subdued))

        val c = section(root, "3. Configurer le Wi-Fi de la maison")
        homeSsid = input("Nom de ton réseau Wi-Fi (SSID)")
        homePassword = input("Mot de passe Wi-Fi", true)
        add(c, homeSsid)
        add(c, homePassword)
        wifiBtn = action("Enregistrer le Wi-Fi dans l'ESP32") { sendHomeWifi() }
        add(c, wifiBtn)
        add(c, label("Après validation, l'ESP32 redémarre et rejoint le réseau de la maison. Le téléphone quitte alors son Wi-Fi temporaire.", 12f, color = subdued))

        val d = section(root, "4. Tableau de bord JK")
        add(d, label("En attente du décodeur JK V19. Aucune valeur fictive n'est affichée.", color = subdued))
        val e = section(root, "Prochainement")
        add(e, label("Lecture SOC/cellules · OTA ESP32 · notifications APK · accès distant sécurisé.", color = subdued))

        val enabled = false
        scanBtn.isEnabled = enabled
        saveBtn.isEnabled = enabled
        wifiBtn.isEnabled = enabled
    }

    private fun state(message: String) = runOnUiThread { status.text = message }

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
        val password = setupCode.text.toString().trim().uppercase()
        if (!Regex("^SmartJK-Setup-[A-F0-9]{6}$").matches(ssid) || password.length != 12) {
            state("Vérifie le SSID SmartJK-Setup-XXXXXX et son code à 12 caractères.")
            return
        }
        releaseConnection()
        activeSetupCode = password
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
        val network = apNetwork ?: throw IllegalStateException("Connecte d'abord l'ESP32.")
        require(path.startsWith("/api/v1/"))
        val conn = network.openConnection(URL("http://192.168.4.1$path")) as HttpURLConnection
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
        runOnUiThread {
            state("ESP32 connecté : ${info.optString("device_id")} · ${info.optString("version")}")
            scanBtn.isEnabled = true
            saveBtn.isEnabled = true
            wifiBtn.isEnabled = true
        }
    }

    private fun scanBle() = async {
        state("Scan BLE en cours sur l'ESP32 (environ 5 secondes)…")
        val response = httpCall("GET", "/api/v1/bms/scan")
        val devices = response.getJSONArray("devices")
        runOnUiThread {
            chosen.clear()
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
        val ssid = homeSsid.text.toString()
        val pass = homePassword.text.toString()
        if (ssid.isBlank() || ssid.length > 32 || pass.length !in 8..63) {
            state("SSID requis ; mot de passe Wi-Fi de 8 à 63 caractères.")
            return
        }
        async {
            state("Enregistrement du Wi-Fi de la maison…")
            val response = httpCall("POST", "/api/v1/wifi",
                JSONObject().put("ssid", ssid).put("password", pass))
            if (response.optBoolean("ok")) {
                switchingToHome = true
                runOnUiThread {
                    homePassword.text.clear()
                    setupCode.text.clear()
                    state("Wi-Fi enregistré. L'ESP32 redémarre : reconnecte le téléphone à ta box.")
                }
                releaseConnection()
            }
        }
    }

    override fun onDestroy() {
        releaseConnection()
        io.shutdownNow()
        super.onDestroy()
    }
}
