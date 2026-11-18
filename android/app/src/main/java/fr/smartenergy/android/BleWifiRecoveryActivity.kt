package fr.smartenergy.android

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Offline recovery only: Android --BLE--> physically BOOT-authorized ESP32.
 * No TCP, LAN, Tailscale or change to existing JK/Voltronic dashboards.
 * User's EXISTING reader code (VPN: gateway token) authenticates AES-GCM.
 * There is no BLE endpoint for inverter or BMS parameter changes.
 */
class BleWifiRecoveryActivity : Activity() {
    private val serviceUuid=UUID.fromString("b5e70001-3a62-4e58-9d12-4e4f44450001")
    private val challengeUuid=UUID.fromString("b5e70002-3a62-4e58-9d12-4e4f44450001")
    private val configUuid=UUID.fromString("b5e70003-3a62-4e58-9d12-4e4f44450001")
    private val statusUuid=UUID.fromString("b5e70004-3a62-4e58-9d12-4e4f44450001")
    private val cryptoDomain="smart-energy-wifi-ble-v1"
    private var scanner:BluetoothLeScanner?=null
    private var scanActive=false
    private var gatt:BluetoothGatt?=null
    private var selected:BluetoothDevice?=null
    private val discovered=linkedMapOf<String,BluetoothDevice>()
    private lateinit var status:TextView
    private lateinit var results:LinearLayout
    private lateinit var code:EditText
    private lateinit var ssid:EditText
    private lateinit var password:EditText
    private lateinit var send:Button
    private lateinit var selection:TextView
    private var confirmed=false

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun note(msg:String) {runOnUiThread { status.text=msg }}
    private fun field(hint:String,secret:Boolean=false)=EditText(this).apply {
        this.hint=hint
        isSingleLine=true
        textSize=15f
        inputType=if(secret)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
    }
    private fun add(root:LinearLayout,v:View) {
        root.addView(v,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(9)})
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll=ScrollView(this)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(16),dp(18),dp(36))
        }
        scroll.addView(root);setContentView(scroll)
        add(root,TextView(this).apply{
            text="Récupération du Wi-Fi par Bluetooth"
            textSize=21f
        })
        add(root,TextView(this).apply {
            text="Sans box ni Tailscale. Reste à proximité de chaque ESP32. "+
                "Sur JK : éloigne temporairement le lecteur des batteries, "+
                "puis maintiens BOOT 6 à 9 secondes. Sur VPN/Voltronic/Deye : "+
                "appuie BOOT pendant au moins 6 secondes après le démarrage. "+
                "Le mode récupération dure 2 minutes."
        })
        add(root,TextView(this).apply{
            text="Le code de l'ESP32 ou le token de la passerelle VPN reste obligatoire. "+
                "Il n'est jamais transmis en clair en Bluetooth."
        })
        code=field("Code actuel de l'ESP (VPN : token privé)",true)
        ssid=field("Nom du nouveau Wi-Fi 2,4 GHz")
        password=field("Mot de passe du nouveau Wi-Fi",true)
        add(root,code);add(root,ssid);add(root,password)
        val search=Button(this).apply {
            text="Rechercher les ESP32 en récupération BLE"
            setOnClickListener {startScan()}
        }
        add(root,search)
        results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        add(root,results)
        selection=TextView(this).apply {text="Aucun ESP32 sélectionné."}
        add(root,selection)
        send=Button(this).apply {
            text="Transmettre le nouveau Wi-Fi à l'ESP32"
            isEnabled=false
            setOnClickListener {submit()}
        }
        add(root,send)
        status=TextView(this).apply {
            text="Démarre avec un seul équipement proche, puis répète sur les autres."
        }
        add(root,status)
        add(root,TextView(this).apply {
            text="Le changement ne modifie ni les associations BMS, ni les paramètres "+
                "onduleurs, ni l'identité Tailscale. Si le Wi-Fi échoue, "+
                "l'ESP32 retrouve automatiquement les anciens identifiants."
        })
    }
    private fun permissionsOk():Boolean {
        val needed=if(Build.VERSION.SDK_INT>=31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val absent=needed.filter {checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(absent.isNotEmpty()){
            requestPermissions(absent.toTypedArray(),817)
            note("Autorise la recherche Bluetooth puis relance la recherche.")
            return false
        }
        return true
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,
                                             grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==817 &&
           grantResults.isNotEmpty() &&
           grantResults.all{it==PackageManager.PERMISSION_GRANTED})startScan()
    }
    private val scanCallback=object:ScanCallback() {
        override fun onScanResult(callbackType:Int,result:ScanResult) {
            val device=result.device ?: return
            val address=try{device.address}catch(_:SecurityException){return}
            if(discovered.containsKey(address))return
            discovered[address]=device
            val name=result.scanRecord?.deviceName ?: try{device.name}catch(_:SecurityException){null}
            runOnUiThread {
                val b=Button(this@BleWifiRecoveryActivity).apply{
                    text=(name?:"ESP Smart Energy")+" · "+address.takeLast(8)
                    isAllCaps=false
                    setOnClickListener{
                        selected=device
                        selection.text="Sélectionné : "+text
                        send.isEnabled=true
                        stopScan()
                    }
                }
                add(results,b)
            }
        }
        override fun onScanFailed(errorCode:Int) {
            note("Recherche Bluetooth indisponible (code $errorCode).")
            scanActive=false
        }
    }
    private fun startScan() {
        if(!permissionsOk())return
        try {
            val adapter=(getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if(adapter==null || !adapter.isEnabled) {
                note("Active le Bluetooth du téléphone.")
                return
            }
            stopScan();discovered.clear();results.removeAllViews()
            selected=null;send.isEnabled=false
            scanner=adapter.bluetoothLeScanner
            val filter=ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
            val settings=ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner?.startScan(listOf(filter),settings,scanCallback)
            scanActive=true
            note("Recherche BLE en cours. Vérifie que BOOT a ouvert la fenêtre de 2 minutes.")
        }catch(ex:SecurityException){
            note("Permission Bluetooth refusée.")
        }catch(ex:Exception){
            note("Recherche BLE impossible : "+ex.javaClass.simpleName)
        }
    }
    private fun stopScan() {
        if(!scanActive)return
        try{scanner?.stopScan(scanCallback)}catch(_:Exception){}
        scanActive=false
    }
    private fun submit() {
        val target=selected ?:run {note("Sélectionne un ESP32.");return}
        if(!permissionsOk())return
        val secret=code.text.toString()
        val newSsid=ssid.text.toString()
        val newPassword=password.text.toString()
        if(secret.length !in 8..128 || newSsid.isEmpty() ||
           newSsid.toByteArray(StandardCharsets.UTF_8).size>32 ||
           newPassword.length !in 8..63){
            note("Vérifie le code privé, le nom du Wi-Fi et le mot de passe (8 à 63 caractères).")
            return
        }
        val json=JSONObject().put("ssid",newSsid)
            .put("password",newPassword).toString().toByteArray(StandardCharsets.UTF_8)
        if(json.size>212){note("Identifiants Wi-Fi trop longs pour la trame BLE.");return}
        confirmed=false;send.isEnabled=false
        try{
            gatt?.close()
            note("Connexion BLE puis échange chiffré avec l'ESP32…")
            gatt=target.connectGatt(this,false,callback,BluetoothDevice.TRANSPORT_LE)
        }catch(ex:Exception){
            send.isEnabled=true;note("Connexion BLE impossible : "+ex.javaClass.simpleName)
        }
    }
    private val callback=object:BluetoothGattCallback(){
        override fun onConnectionStateChange(g:BluetoothGatt,statusCode:Int,newState:Int){
            if(statusCode!=BluetoothGatt.GATT_SUCCESS){
                note("Connexion BLE interrompue ($statusCode).")
                runOnUiThread {send.isEnabled=true}
                try{g.close()}catch(_:Exception){}
                return
            }
            if(newState==BluetoothProfile.STATE_CONNECTED) {
                try {
                    if(!g.requestMtu(247))g.discoverServices()
                }catch(_:Exception){g.discoverServices()}
            }else if(newState==BluetoothProfile.STATE_DISCONNECTED) {
                if(!confirmed)note("ESP32 déconnecté avant confirmation de la configuration.")
                try{g.close()}catch(_:Exception){}
                runOnUiThread {send.isEnabled=true}
            }
        }
        override fun onMtuChanged(g:BluetoothGatt,mtu:Int,status:Int){
            g.discoverServices()
        }
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int) {
            val nonce=g.getService(serviceUuid)?.getCharacteristic(challengeUuid)
            if(status!=BluetoothGatt.GATT_SUCCESS || nonce==null) {
                note("Firmware ESP32 incompatible avec la récupération Wi-Fi BLE.")
                return
            }
            if(!g.readCharacteristic(nonce))note("Lecture du défi BLE impossible.")
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g:BluetoothGatt,
                                          c:BluetoothGattCharacteristic,status:Int){
            onRead(g,c,c.value?:byteArrayOf(),status)
        }
        override fun onCharacteristicRead(g:BluetoothGatt,
                                          c:BluetoothGattCharacteristic,
                                          value:ByteArray,status:Int){
            onRead(g,c,value,status)
        }
        private fun onRead(g:BluetoothGatt,c:BluetoothGattCharacteristic,
                           value:ByteArray,status:Int){
            if(status!=BluetoothGatt.GATT_SUCCESS){
                note("Erreur lors de la vérification BLE ($status).")
                runOnUiThread {send.isEnabled=true}
                return
            }
            if(c.uuid==challengeUuid)writeEncrypted(g,value)
            else if(c.uuid==statusUuid) {
                if(value.toString(StandardCharsets.US_ASCII)=="OK"){
                    confirmed=true
                    note("Nouveau Wi-Fi enregistré provisoirement. "+
                        "L'ESP32 redémarre, teste la box et revient aux anciens réglages si nécessaire.")
                }else note("ESP32 n'a pas confirmé la configuration. Vérifie son code privé.")
                runOnUiThread {send.isEnabled=true}
            }
        }
        override fun onCharacteristicWrite(g:BluetoothGatt,
                                           characteristic:BluetoothGattCharacteristic,
                                           statusCode:Int){
            if(statusCode!=BluetoothGatt.GATT_SUCCESS){
                note("Configuration refusée. Vérifie le code de cet ESP32 et le BOOT.")
                runOnUiThread{send.isEnabled=true}
                return
            }
            val confirmation=g.getService(serviceUuid)?.getCharacteristic(statusUuid)
            if(confirmation==null || !g.readCharacteristic(confirmation))
                note("Confirmation de l'ESP32 indisponible : ne considère pas le Wi-Fi modifié.")
        }
    }
    @Suppress("DEPRECATION")
    private fun writeEncrypted(g:BluetoothGatt,challenge:ByteArray) {
        if(challenge.size!=16){note("Défi BLE invalide.");return}
        try {
            // No secret, Wi-Fi credential or token is logged or sent in plaintext.
            val secret=code.text.toString().toByteArray(StandardCharsets.UTF_8)
            val text=JSONObject().put("ssid",ssid.text.toString())
                .put("password",password.text.toString())
                .toString().toByteArray(StandardCharsets.UTF_8)
            val domain=cryptoDomain.toByteArray(StandardCharsets.UTF_8)
            val mac=Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret,"HmacSHA256"))
            val key=mac.doFinal(domain+challenge)
            secret.fill(0)
            val iv=ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),
                GCMParameterSpec(128,iv))
            cipher.updateAAD(challenge)
            val cipherAndTag=cipher.doFinal(text)
            text.fill(0);key.fill(0)
            val tag=cipherAndTag.copyOfRange(cipherAndTag.size-16,cipherAndTag.size)
            val body=cipherAndTag.copyOfRange(0,cipherAndTag.size-16)
            val packet=iv+tag+body
            if(packet.size>240)throw IllegalArgumentException("BLE message too long")
            val change=g.getService(serviceUuid)?.getCharacteristic(configUuid)
                ?:throw IllegalStateException("BLE write not supported")
            if(Build.VERSION.SDK_INT>=33) {
                val rc=g.writeCharacteristic(change,packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if(rc!=BluetoothGatt.GATT_SUCCESS)
                    throw IllegalStateException("GATT write "+rc)
            } else {
                change.value=packet
                if(!g.writeCharacteristic(change))throw IllegalStateException("GATT write failed")
            }
        }catch(ex:Exception) {
            note("Chiffrement ou transmission BLE impossible : "+ex.javaClass.simpleName)
            runOnUiThread {send.isEnabled=true}
        }
    }
    override fun onDestroy() {
        stopScan()
        try{gatt?.disconnect();gatt?.close()}catch(_:Exception){}
        super.onDestroy()
    }
}
