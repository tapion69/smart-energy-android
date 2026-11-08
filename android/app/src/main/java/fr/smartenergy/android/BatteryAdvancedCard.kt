package fr.smartenergy.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Native read-only display. All inputs are verified BMS frames or unavailable. */
class BatteryAdvancedCard(context: Context) : LinearLayout(context) {
    private val muted = Color.rgb(163,182,201)
    private val ink = Color.rgb(239,247,254)
    private val mint = Color.rgb(57,221,166)
    private val orange = Color.rgb(255,191,109)
    private var lastSignature = ""

    init { orientation = VERTICAL; setPadding(0,8,0,16) }
    private fun dp(x:Int) = (x*resources.displayMetrics.density+0.5f).toInt()
    private fun txt(s:String,size:Float=13f,bold:Boolean=false,color:Int=ink) =
        TextView(context).apply {
            text=s; textSize=size;setTextColor(color);includeFontPadding=false
            if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        }
    private fun panel(title:String, sub:String=""):LinearLayout {
        val p=LinearLayout(context).apply {
            orientation=VERTICAL
            setPadding(dp(14),dp(13),dp(14),dp(14))
            background=GradientDrawable().apply {
                setColor(Color.rgb(23,36,54))
                cornerRadius=dp(15).toFloat()
                setStroke(dp(1),Color.rgb(55,75,96))
            }
        }
        p.addView(txt(title,17f,true))
        if(sub.isNotEmpty())p.addView(txt(sub,12f,false,muted),
            LayoutParams(-1,-2).apply {topMargin=dp(6)})
        addView(p,LayoutParams(-1,-2).apply {bottomMargin=dp(12)})
        return p
    }
    private fun row(p:LinearLayout,k:String,v:String,tint:Int=ink) {
        val line=LinearLayout(context).apply {
            orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(0,dp(6),0,dp(6))
        }
        line.addView(txt(k,12f,false,muted),LayoutParams(0,-2,1f))
        line.addView(txt(v,13f,true,tint).apply{gravity=Gravity.END},
            LayoutParams(-2,-2).apply{leftMargin=dp(8)})
        p.addView(line)
    }
    private fun num(o:JSONObject,k:String,digits:Int=2,unit:String=""):String {
        if(!o.has(k)||o.isNull(k))return "—"
        val x=o.optDouble(k,Double.NaN)
        if(!x.isFinite())return "—"
        return String.format(Locale.FRANCE,"%."+digits+"f",x)+
            (if(unit.isEmpty())"" else " "+unit)
    }
    private fun safeIdentityText(raw:String,key:String):Boolean {
        if(raw.length < 2 || raw.length > 32) return false
        if(!raw.all { it in 'a'..'z' || it in 'A'..'Z' ||
                        it in '0'..'9' || it in " ._-" }) return false
        return when(key) {
            "manufacturing_date" -> Regex("^[0-9]{6}([0-9]{2})?$").matches(raw)
            "serial_number" -> raw.length >= 5
            else -> true
        }
    }

    private fun flag(o:JSONObject,k:String):String {
        if(!o.has(k)||o.isNull(k))return "—"
        return if(o.optBoolean(k))"Oui" else "Non"
    }
    fun measurements(index:Int, device:JSONObject) {
        val signature="m"+index+device.toString()
        if(lastSignature==signature)return
        lastSignature=signature
        removeAllViews()
        val values=device.optJSONObject("values")
        val header=panel("JK "+(index+1)+" · États et alarmes")
        row(header,"Bluetooth",if(device.optBoolean("ble_connected"))"Connecté" else "Déconnecté")
        if(!device.optBoolean("available")||values==null) {
            row(header,"Diagnostic",device.optString("state","Données indisponibles"))
            return
        }
        row(header,"Charge MOS",flag(values,"charging_mos"))
        row(header,"Décharge MOS",flag(values,"discharging_mos"))
        row(header,"Précharge",flag(values,"precharging"))
        row(header,"Chauffage",flag(values,"heating"))
        if(values.has("charge_status"))row(header,"Phase charge",
            when(values.optInt("charge_status")) {
                0->"Bulk";1->"Absorption";2->"Float";else->"Inconnue"
            })
        if(values.has("balance_action"))row(header,"Équilibrage",
            when(values.optInt("balance_action")) {
                0->"Inactif";1->"Charge";2->"Décharge";else->"Inconnu"
            })
        val mask=values.optLong("error_mask")
        val bits=JkAlarmLabels.activeBits(mask)
        if(!values.has("error_mask"))row(header,"Alarmes","Non disponibles")
        else if(bits.isEmpty())row(header,"Alarmes actives","Aucune")
        else for(bit in bits)row(header,"Alarme "+bit,
            JkAlarmLabels.label(bit),orange)

        val energy=panel("Capacité, énergie et équilibrage")
        for((label,key,unit) in listOf(
            Triple("Capacité restante","capacity_remaining_ah","Ah"),
            Triple("Capacité nominale","nominal_capacity_ah","Ah"),
            Triple("Santé batterie","soh_pct","%"),
            Triple("Capacité cyclée","total_cycle_capacity_ah","Ah"),
            Triple("Courant équilibrage","balance_current_a","A"),
            Triple("Puissance charge","charge_power_w","W"),
            Triple("Puissance décharge","discharge_power_w","W")
        ))if(values.has(key))row(energy,label,num(values,key,if(unit=="%") 0 else 2,unit))
        if(values.has("runtime_s")) {
            val s=values.optLong("runtime_s")
            row(energy,"Fonctionnement",(s/86400).toString()+" j "+
                ((s%86400)/3600).toString()+" h")
        }

        val temps=panel("Sondes de température","Les sondes absentes restent indisponibles")
        for((label,key) in listOf(
            "Sonde 1" to "temp1_c","Sonde 2" to "temp2_c",
            "MOS" to "mos_temp_c","Sonde 3" to "temp3_c",
            "Sonde 4" to "temp4_c","Sonde 5" to "temp5_c"
        ))row(temps,label,num(values,key,1,"°C"))

        val cellCard=panel("Cellules — tension et résistance",
            "Tensions en V, résistances en Ω. Les valeurs absentes sont masquées.")
        val cells=values.optJSONArray("cells")?:JSONArray()
        for(i in 0 until cells.length()){
            val cell=cells.optJSONObject(i)?:continue
            row(cellCard,"Cellule "+cell.optInt("number"),
                num(cell,"voltage_v",3,"V")+" · "+num(cell,"resistance_ohm",3,"Ω"),
                if(cell.optBoolean("wire_warning"))orange else ink)
        }
        val diag=panel("Diagnostic Bluetooth")
        row(diag,"Connexions",device.optInt("connections").toString())
        row(diag,"Déconnexions",device.optInt("disconnections").toString())
        row(diag,"CRC rejetés",device.optInt("bad_crc_count").toString())
        row(diag,"Mesures rejetées",device.optInt("rejected_measurements").toString())
        row(diag,"Âge mesure",device.optInt("age_seconds").toString()+" s")
    }

    fun settings(index:Int, device:JSONObject?, telemetry:JSONObject? = null) {
        val liveValues = telemetry?.optJSONObject("values")
        val statusSignature = listOf(
            telemetry?.optBoolean("available"),
            telemetry?.optBoolean("ble_connected"),
            liveValues?.optString("frame_layout"),
            liveValues?.optInt("cell_count")
        ).joinToString()
        val signature = "s" + index + (device?.toString() ?: "") + statusSignature
        if (lastSignature == signature) return
        lastSignature = signature
        removeAllViews()
        val header = panel("JK " + (index + 1) + " · Paramètres",
            "Lecture seule · aucun paramètre n'est modifié")
        if (device == null) {
            row(header, "État", "Paramètres JK non reçus")
        }
        val s=device?.optJSONObject("settings")
        if(s==null||!s.optBoolean("available")){
            row(header,"État","Trame paramètres JK non reçue ou périmée")
        } else {
            row(header,"Dernière lecture",s.optInt("age_seconds").toString()+" s")
            val array=s.optJSONArray("items")?:JSONArray()
            val groups=linkedMapOf<String,MutableList<JSONObject>>()
            for(i in 0 until array.length()){
                val p=array.optJSONObject(i)?:continue
                groups.getOrPut(p.optString("group","Autres")){mutableListOf()}.add(p)
            }
            val order=listOf("Batterie","Tensions","Courants","Équilibrage",
                "Températures protection","Interrupteurs","Énergie")
            for(group in order+groups.keys.filter{it !in order}){
                val entries=groups[group]?:continue
                val panel=panel(group)
                for(p in entries){
                    val title=p.optString("label",p.optString("key"))
                    val result=if(p.optString("type")=="bool") {
                        if(p.optBoolean("value"))"Activé" else "Désactivé"
                    } else {
                        val unit=p.optString("unit")
                        num(p,"value",if(unit=="V"||unit=="A"||unit=="Ah") 3 else
                            if(unit=="°C") 1 else 0,unit)
                    }
                    row(panel,title,result)
                }
            }
        }
        val info = device?.optJSONObject("info")
        val p = panel("Informations du BMS", "Décodage strict : aucune identité inventée")
        val model = info?.optString("model", "") ?: ""
        val recognizedModel = Regex("^(JK|PB|Jik)[A-Za-z0-9 ._-]{1,14}$",
            RegexOption.IGNORE_CASE).matches(model)
        if (info == null || !info.optBoolean("available") || !recognizedModel) {
            val explanation = when {
                info?.optString("reason") == "unrecognized_type3_identity_layout" ->
                    "La trame d'identité JK 0x03 n'utilise pas le format reconnu."
                info?.optBoolean("available") == true && !recognizedModel ->
                    "La trame JK contient une identité non fiable : texte masqué."
                else -> "La trame d'identité JK 0x03 n'est pas disponible."
            }
            p.addView(txt(explanation, 13f, color = muted),
                LayoutParams(-1, -2).apply { topMargin = dp(10) })
            // Verified telemetry is available even if the vendor-specific
            // identity frame is missing or not implemented on older readers.
            if (telemetry?.optBoolean("available") == true && liveValues != null) {
                row(p, "Connexion Bluetooth",
                    if (telemetry.optBoolean("ble_connected")) "Connectée" else "Déconnectée")
                val profile = liveValues.optString("frame_layout", "")
                if (profile in setOf("JK02_24S", "JK02_32S"))
                    row(p, "Format de mesures", profile + " (profil, pas modèle BMS)")
                val cellCount = liveValues.optInt("cell_count", 0)
                if (cellCount in 4..32) row(p, "Cellules actives", cellCount.toString())
            }
            if (info?.has("unrecognized_frames") == true &&
                info.optInt("unrecognized_frames") > 0)
                row(p, "Trames d'identité rejetées",
                    info.optInt("unrecognized_frames").toString())
            p.addView(txt("Le modèle, la version et le numéro de série exacts "
                + "nécessitent une trame 0x03 correctement reconnue par le firmware JK.",
                12f, color = muted),
                LayoutParams(-1, -2).apply { topMargin = dp(10) })
        } else {
            row(p,"Modèle",model)
            for((label,key) in listOf(
                "Nom" to "name",
                "Version matérielle" to "hardware_version",
                "Version logicielle" to "software_version",
                "Fabrication" to "manufacturing_date",
                "Numéro de série" to "serial_number"
            )) {
                val value=info.optString(key,"")
                if (safeIdentityText(value,key)) row(p,label,value)
            }
            // Old JK 0.2.0 can emit plausible-looking counters from an unknown
            // binary layout. Show them only when the strict 0.2.1+ validation
            // metadata is available; never silently present fake counters.
            if(info.has("unrecognized_frames")) {
                if(info.has("power_on_count") &&
                    info.optLong("power_on_count",-1) in 1..999999)
                    row(p,"Allumages",info.optLong("power_on_count").toString())
                if(info.has("uptime_hours") &&
                    info.optDouble("uptime_hours",Double.NaN) in 0.1..87600.0)
                    row(p,"Fonctionnement",num(info,"uptime_hours",1,"h"))
            } else {
                row(p,"Compteurs","Non confirmés par le firmware JK actuel")
            }
        }
    }
}
